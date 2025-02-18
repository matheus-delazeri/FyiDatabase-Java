package engine.storage.sorted;

import engine.exceptions.DataBaseException;
import engine.file.FileManager;
import engine.virtualization.interfaces.BlockManager;
import engine.virtualization.record.Record;
import engine.virtualization.record.RecordInfoExtractor;
import engine.virtualization.record.RecordStream;
import engine.virtualization.record.instances.GenericRecord;
import engine.virtualization.record.storage.btree.BTreeStorage;
import lib.BigKey;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BTreeStorageRecord extends PkStorageRecord<BigKey> {

    private BTreeStorage btree;
    private BlockManager blockManager;

    public BTreeStorageRecord(FileManager fm,RecordInfoExtractor extractor, int sizeOfKey, int sizeOfEntry) {
        blockManager = new BlockManager();
        btree = new BTreeStorage(fm,blockManager, extractor,sizeOfKey,sizeOfEntry);
    }

    @Override
    public synchronized void restart() {
        // TODO
        ArrayList<BigKey> pks = new ArrayList<>();
        RecordStream<BigKey> it = this.read(null);
        while(it.hasNext()){
            it.next();
            pks.add(it.getKey());
        }
        for (BigKey pk:
             pks) {
            this.delete(pk);
        }

    }

    @Override
    public void flush() {
        btree.save();
    }

    @Override
    public RecordStream read(BigKey key) {
        return new RecordStream() {

            Iterator<Map.Entry<BigKey, ByteBuffer>> iterator = null;

            Map.Entry<BigKey, ByteBuffer> current = null;

            @Override
            public void open() {
                if(key == null)
                    iterator = btree.iterator();
                else
                    iterator = btree.iterator(key);
            }

            @Override
            public void close() {
                iterator = null;
            }

            @Override
            public BigKey getKey() {
                if(current!=null)return current.getKey();
                return null;
            }

            @Override
            public Record getRecord() {
                if(current!=null)return new GenericRecord(current.getValue().array());
                return null;
            }

            @Override
            public void update(Record r) {
                throw new DataBaseException("BTreeStorageRecord->iterator->update","Essa operação não é suportada para essa estrutura.");
            }

            @Override
            public void reset() {
                this.close();
                this.open();
            }

            @Override
            public boolean hasNext() {
                if(iterator==null)return false;
                return iterator.hasNext();
            }

            @Override
            public Record next() {
                if(iterator==null)return null;
                current = iterator.next();
                return getRecord();
            }
        };
    }

    @Override
    public synchronized void write(BigKey key, Record r) {
        btree.insert(key, ByteBuffer.wrap(r.getData()));
    }

    @Override
    public synchronized void write(List<Map.Entry<BigKey, Record>> list) {
        for(Map.Entry<BigKey, Record> entry:list){
            btree.insert(entry.getKey(),ByteBuffer.wrap(entry.getValue().getData()));
        }
    }

    @Override
    public synchronized void delete(BigKey key) {
        btree.remove(key);
    }

    @Override
    public void close() {
        this.flush();
    }


}
