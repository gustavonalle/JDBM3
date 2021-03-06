/*******************************************************************************
 * Copyright 2010 Cees De Groot, Alex Boisvert, Jan Kotek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/


package net.kotek.jdbm;

import javax.crypto.Cipher;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;

/**
 * This class represents a random access file as a set of fixed size
 * records. Each record has a physical record number, and records are
 * cached in order to improve access.
 * <p/>
 * The set of dirty records on the in-use list constitutes a transaction.
 * Later on, we will send these records to some recovery thingy.
 * <p/>
 * RecordFile is splited between more files, each with max size 1GB.
 */
final class RecordFile {
    final TransactionManager txnMgr;


    /**
     * Blocks currently locked for read/update ops. When released the block goes
     * to the dirty or clean list, depending on a flag.  The file header block is
     * normally locked plus the block that is currently being read or modified.
     *
     * @see BlockIo#isDirty()
     */
    private final LongHashMap<BlockIo> inUse = new LongHashMap<BlockIo>();

    /**
     * Blocks whose state is dirty.
     */
    private final LongHashMap<BlockIo> dirty = new LongHashMap<BlockIo>();
    /**
     * Blocks in a <em>historical</em> transaction(s) that have been written
     * onto the log but which have not yet been committed to the database.
     */
    private final LongHashMap<BlockIo> inTxn = new LongHashMap<BlockIo>();


    // transactions disabled?
    private boolean transactionsDisabled = false;

    /**
     * A block of clean data to wipe clean pages.
     */
    static final byte[] CLEAN_DATA = new byte[Storage.BLOCK_SIZE];


    private Storage storage;
    private Cipher cipherOut;
    private Cipher cipherIn;


    /**
     * Creates a new object on the indicated filename. The file is
     * opened in read/write mode.
     *
     * @param fileName the name of the file to open or create, without
     *                 an extension.
     * @throws IOException whenever the creation of the underlying
     *                     RandomAccessFile throws it.
     */
    RecordFile(String fileName, boolean readonly,  boolean transactionsDisabled, Cipher cipherIn, Cipher cipherOut, boolean useRandomAccessFile) throws IOException {
        this.cipherIn = cipherIn;
        this.cipherOut = cipherOut;
        this.transactionsDisabled = transactionsDisabled;
        if(fileName == null){
            this.storage = new StorageMemory(transactionsDisabled);
        }else if (fileName.contains("!/"))
            this.storage = new StorageZip(fileName);
        else if(useRandomAccessFile)
            this.storage = new StorageDisk(fileName,readonly);
        else
            this.storage = new StorageDiskMapped(fileName,readonly,transactionsDisabled);

        if (this.storage.isReadonly() && !readonly)
            throw new IllegalArgumentException("This type of storage is readonly, you should call readonly() on DBMaker");
        if (!readonly && !transactionsDisabled) {
            txnMgr = new TransactionManager(this, storage, cipherIn, cipherOut);
        } else {
            txnMgr = null;
        }
    }

    public RecordFile(String filename) throws IOException {
        this(filename, false, false, null, null,false);
    }


    /**
     * Gets a block from the file. The returned byte array is
     * the in-memory copy of the record, and thus can be written
     * (and subsequently released with a dirty flag in order to
     * write the block back).
     *
     * @param blockid The record number to retrieve.
     */
    BlockIo get(long blockid) throws IOException {

        // try in transaction list, dirty list, free list
        BlockIo node = inTxn.get(blockid);
        if (node != null) {
            inTxn.remove(blockid);
            inUse.put(blockid, node);
            return node;
        }
        node = dirty.get(blockid);
        if (node != null) {
            dirty.remove(blockid);
            inUse.put(blockid, node);
            return node;
        }


        // sanity check: can't be on in use list
        if (inUse.get(blockid) != null) {
            throw new Error("double get for block " + blockid);
        }

        //read node from file
        if (cipherOut == null) {
            node = new BlockIo(blockid,storage.read(blockid));
        } else {
            //decrypt if needed
            ByteBuffer b = storage.read(blockid);
            byte[] bb;
            if(b.hasArray()){
                bb = b.array();
            }else{
                bb = new byte[Storage.BLOCK_SIZE];
                b.position(0);
                b.get(bb, 0, Storage.BLOCK_SIZE);
            }
            if (!Utils.allZeros(bb)) try {
                bb = cipherOut.doFinal(bb);
                node = new BlockIo(blockid, ByteBuffer.wrap(bb));
                } catch (Exception e) {
                throw new IOError(e);
            }else {
                node = new BlockIo(blockid, ByteBuffer.wrap(RecordFile.CLEAN_DATA).asReadOnlyBuffer());
            }
        }


        inUse.put(blockid, node);
        node.setClean();
        return node;
    }


    /**
     * Releases a block.
     *
     * @param blockid The record number to release.
     * @param isDirty If true, the block was modified since the get().
     */
    void release(final long blockid, final boolean isDirty) throws IOException {
        BlockIo node = inUse.get(blockid);
        if (node == null)
            throw new IOException();
        if (!node.isDirty() && isDirty)
            node.setDirty();
        release(node);
    }

    /**
     * Releases a block.
     *
     * @param block The block to release.
     */
    void release(final BlockIo block) throws IOException {
        final long key = block.getBlockId();
        inUse.remove(key);
        if (block.isDirty()) {
            // System.out.println( "Dirty: " + key + block );
            dirty.put(key, block);
        } else {
            if (!transactionsDisabled && block.isInTransaction()) {
                inTxn.put(key, block);
            }
        }
    }

    /**
     * Discards a block (will not write the block even if it's dirty)
     *
     * @param block The block to discard.
     */
    void discard(BlockIo block) {
        long key = block.getBlockId();
        inUse.remove(key);
    }

    /**
     * Commits the current transaction by flushing all dirty buffers
     * to disk.
     */
    void commit() throws IOException {
        // debugging...
        if (!inUse.isEmpty() && inUse.size() > 1) {
            showList(inUse.valuesIterator());
            throw new Error("in use list not empty at commit time ("
                    + inUse.size() + ")");
        }

        //  System.out.println("committing...");

        if (dirty.size() == 0) {
            // if no dirty blocks, skip commit process
            return;
        }

        if (!transactionsDisabled) {
            txnMgr.start();
        }

        //sort block by IDs
        long[] blockIds = new long[dirty.size()];
        int c = 0;
        for (Iterator<BlockIo> i = dirty.valuesIterator(); i.hasNext(); ) {
            blockIds[c] = i.next().getBlockId();
            c++;
        }
        Arrays.sort(blockIds);

        for (long blockid : blockIds) {
            BlockIo node = dirty.get(blockid);

            // System.out.println("node " + node + " map size now " + dirty.size());
            if (transactionsDisabled) {
                if(cipherIn !=null)                    
                   storage.write(node.getBlockId(), ByteBuffer.wrap(Utils.encrypt(cipherIn, node.getData())));
                else
                   storage.write(node.getBlockId(),node.getData());
                node.setClean();
            } else {
                txnMgr.add(node);
                inTxn.put(node.getBlockId(), node);
            }
        }
        dirty.clear();
        if (!transactionsDisabled) {
            txnMgr.commit();
        }
    }


    /**
     * Rollback the current transaction by discarding all dirty buffers
     */
    void rollback() throws IOException {
        // debugging...
        if (!inUse.isEmpty()) {
            showList(inUse.valuesIterator());
            throw new Error("in use list not empty at rollback time ("
                    + inUse.size() + ")");
        }
        //  System.out.println("rollback...");
        dirty.clear();

        txnMgr.synchronizeLogFromDisk();

        if (!inTxn.isEmpty()) {
            showList(inTxn.valuesIterator());
            throw new Error("in txn list not empty at rollback time ("
                    + inTxn.size() + ")");
        }
        ;
    }

    /**
     * Commits and closes file.
     */
    void close() throws IOException {
        if (!dirty.isEmpty()) {
            commit();
        }

        if(!transactionsDisabled){
            txnMgr.shutdown();
        }

        if (!inTxn.isEmpty()) {
            showList(inTxn.valuesIterator());
            throw new Error("In transaction not empty");
        }

        // these actually ain't that bad in a production release
        if (!dirty.isEmpty()) {
            System.out.println("ERROR: dirty blocks at close time");
            showList(dirty.valuesIterator());
            throw new Error("Dirty blocks at close time");
        }
        if (!inUse.isEmpty()) {
            System.out.println("ERROR: inUse blocks at close time");
            showList(inUse.valuesIterator());
            throw new Error("inUse blocks at close time");
        }

        storage.forceClose();
    }


    /**
     * Force closing the file and underlying transaction manager.
     * Used for testing purposed only.
     */
    void forceClose() throws IOException {
        if(!transactionsDisabled){
            txnMgr.forceClose();
        }
        storage.forceClose();
    }

    /**
     * Prints contents of a list
     */
    private void showList(Iterator<BlockIo> i) {
        int cnt = 0;
        while (i.hasNext()) {
            System.out.println("elem " + cnt + ": " + i.next());
            cnt++;
        }
    }

    /**
     * Synchs a node to disk. This is called by the transaction manager's
     * synchronization code.
     */
    void synch(BlockIo node) throws IOException {
        ByteBuffer data = node.getData();
        if (data != null) {
            if(cipherIn!=null)
                storage.write(node.getBlockId(), ByteBuffer.wrap(Utils.encrypt(cipherIn, data)));
            else
                storage.write(node.getBlockId(),  data);
        }
    }

    /**
     * Releases a node from the transaction list, if it was sitting
     * there.
     */
    void releaseFromTransaction(BlockIo node)
            throws IOException {
        inTxn.remove(node.getBlockId());
    }

    /**
     * Synchronizes the file.
     */
    void sync() throws IOException {
        storage.sync();
    }

    public int getDirtyPageCount() {
        return dirty.size();
    }
}
