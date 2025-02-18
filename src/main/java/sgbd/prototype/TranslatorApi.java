package sgbd.prototype;

import engine.exceptions.DataBaseException;
import engine.file.streams.ReadByteStream;
import engine.util.Util;
import engine.virtualization.record.Record;
import engine.virtualization.record.RecordInfoExtractor;
import engine.virtualization.record.instances.GenericRecord;
import lib.BigKey;
import sgbd.prototype.column.Column;
import sgbd.prototype.query.fields.Field;
import sgbd.util.global.UtilConversor;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class TranslatorApi implements RecordInfoExtractor, Iterable<Column>{

    private final int headerSize;
    private final byte[] headerBuffer;


    private final ArrayList<Column> columns;
    private final HashMap<Integer,Integer> headerPosition;

    private final int primaryKeySize;
    private byte[] bufferArrayPk;

    protected TranslatorApi(ArrayList<Column> columns){
        this.columns=columns;
        int headerSize = 1;
        this.headerPosition = new HashMap<>();
        /*
        0 - 256
                0-253 -> ja é o tamanho => 1 byte
                254 -> deve ler um short => 3 byes
                255 -> deve ler um int   => 5 bytes => unico pior caso
                5 bytes
                30 = nome -> 14 => 1 byte
                44 = email -> 20 => 1 byte
                64 = descricao -> 500 => 3 bytes
                //
                12 bytes
                int == 4 bytes
                [int 30,int 44,int 64]
                30 = nome -> 14
                44 = email -> 20
                64 = descricao -> 500
        */
        int aux=1;
        int sizePk = 0;
        for (Column c:columns){
            if(c.camBeNull()){
                this.headerPosition.put(columns.indexOf(c),(headerSize-1)*8+aux);
                aux++;
            }
            if(aux>=8){
                headerSize++;
                aux=0;
            }
            if(c.isPrimaryKey()){
                sizePk +=c.getSize();
            }
        }
        this.headerSize = headerSize;
        primaryKeySize = sizePk;
        bufferArrayPk = new byte[sizePk];
        headerBuffer = new byte[headerSize];
    }

    public int maxRecordSize(){
        int size = headerSize;
        for(Column c: columns){
            size+=c.getSize();
            if(c.isDinamicSize())size+=4;
        }
        return size;
    }

    public BigKey getPrimaryKey(RowData rw){
        ByteBuffer buffer = ByteBuffer.allocate(primaryKeySize);
        for(Column c:columns){
            if(c.isPrimaryKey()){
                byte[] arr = rw.getData(c.getName());
                if(arr==null){
                    arr = new byte[c.getSize()];
                    Arrays.fill(arr,(byte)0);
                }
                buffer.put(arr);
            }else{
                break;
            }
        }
        return new BigKey(buffer.array());
    }

    public ArrayList<Column> getColumns(){
        return columns;
    }

    public int getPrimaryKeySize(){
        return primaryKeySize;
    }

    @Override
    public synchronized BigKey getPrimaryKey(ByteBuffer rbs) {
        rbs.get(this.headerSize,bufferArrayPk,0,primaryKeySize);
        return new BigKey(bufferArrayPk,true);
    }

    @Override
    public synchronized BigKey getPrimaryKey(ReadByteStream rbs) {
        rbs.read(this.headerSize,bufferArrayPk,0,primaryKeySize);
        return new BigKey(bufferArrayPk,true);
    }

    @Override
    public boolean isActiveRecord(ByteBuffer rbs) {
        return false;
    }

    @Override
    public synchronized boolean isActiveRecord(ReadByteStream rbs) {
        rbs.read(0,bufferArrayPk,0,1);
        return (bufferArrayPk[0]&0x1) !=0;
    }

    @Override
    public void setActiveRecord(Record r, boolean active) {
        byte[] arr = r.getData();
        arr[0] = (byte)( (arr[0]&(~0x1)) | ((active)?0x1:0x0));
    }

    public void validateRowData(RowData rw){
        if(rw.isValid())return;
        for(Column c: columns){
            byte[] data = rw.getData(c.getName());
            if(data == null){
                if(!c.camBeNull()){
                    throw new DataBaseException("RecordTranslateApi->convertToRecord","Coluna "+c.getName()+" não pode ser nula!");
                }else if(c.isPrimaryKey()){
                    throw new DataBaseException("RecordTranslateApi->convertToRecord","Coluna "+c.getName()+" não pode ser nula!");
                }
            }else{
                if(c.isDinamicSize()){
                    if(data.length>c.getSize()){
                        throw new DataBaseException("RecordTranslateApi->convertToRecord","Dado passado para a coluna "+c.getName()+" é maior que o limite: "+c.getSize());
                    }
                }else{
                    if(data.length>c.getSize()){
                        throw new DataBaseException("RecordTranslateApi->convertToRecord","Dado passado para a coluna "+c.getName()+" é diferente do tamanho fixo: "+c.getSize());
                    }
                }
            }
        }
        rw.setValid();
    }
    public synchronized RowData convertBinaryToRowData(byte[] data, Map<String,Column> meta,boolean hasHeader,boolean onlyPrimaryKey) {
        RowData row = new RowData();

        int selecteds = 0;
        int offset = (hasHeader)?this.headerSize:0;

        byte[] header = headerBuffer;
        if(hasHeader)
            System.arraycopy(data,0,header,0,this.headerSize);

        int headerPointer = 1;

        for(Column c: columns){
            if(meta!=null && selecteds >= meta.size())break;
            if(onlyPrimaryKey && !c.isPrimaryKey())break;
            boolean checkColumn = meta==null || meta.containsKey(c.getName());
            if(checkColumn)selecteds++;
            if(c.camBeNull() && hasHeader){
                try {
                    if ((header[headerPointer / 8] & (1 << headerPointer%8)) != 0) {
                        //campo é nulo
                        continue;
                    }
                }finally {
                    headerPointer++;
                }
            }
            int size = c.getSize();
            if (c.isDinamicSize()) {
                size = UtilConversor.byteArrayToInt(Arrays.copyOfRange(data, offset, offset + 4));
                offset += 4;
                if(checkColumn) {
                    byte[] arr = Arrays.copyOfRange(data, offset, offset + size);
                    row.setField(c.getName(), Field.createField(c,new BData(arr)), c);
                }

            } else {
                if(checkColumn) {
                    byte[] arr = Arrays.copyOfRange(data, offset, offset + c.getSize());
                    row.setField(c.getName(), Field.createField(c,new BData(arr)), c);
                }
            }
            offset += size;
        }
        return row;
    }

    public synchronized RowData convertToRowData(Record r, Map<String,Column> meta){
        return convertBinaryToRowData(r.getData(),meta,true,false);
    }

    public Record convertToRecord(RowData rw){
        this.validateRowData(rw);
        byte[] header = new byte[this.headerSize];
        ArrayList<byte[]> dados = new ArrayList<>();
        int size = this.headerSize;
        header[0] |= 1;
        for(Column c: columns){
            byte[] data = rw.getData(c.getName());
            if(data == null){
                if(c.camBeNull()){
                    int posHeader = headerPosition.get(columns.indexOf(c));
                    header[posHeader/8] |= 1<<(posHeader%8);
                }
            }else{
                if(c.isDinamicSize()){
                    ByteBuffer buff = ByteBuffer.allocate(4);
                    buff.order(ByteOrder.LITTLE_ENDIAN);
                    buff.putInt(data.length);
                    byte[] indice = buff.array();
                    dados.add(indice);
                    dados.add(data);
                    size+=indice.length;
                    size+=data.length;
                }else{
                    dados.add(data);
                    size+=c.getSize();
                    if(data.length<c.getSize()){
                        dados.add(new byte[c.getSize()-data.length]);
                    }
                }
            }
        }
        byte[] bufferRecord = new byte[size];
        int offset = this.headerSize;
        System.arraycopy(header,0,bufferRecord,0,header.length);
        for(byte[] data:dados){
            System.arraycopy(data,0,bufferRecord,offset,data.length);
            offset+=data.length;
        }
        return new GenericRecord(bufferRecord);
    }

    public Map<String,Column> generateMetaInfo(List<String> select){
        HashMap<String,Column> map = new HashMap<>();
        for (Column c: columns) {
            if(select == null || select.contains(c.getName()))
                map.put(c.getName(),c);
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public Iterator<Column> iterator() {
        return columns.iterator();
    }
}
