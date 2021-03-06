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

import java.io.IOError;
import java.io.IOException;
import java.util.*;


/**
 * Wrapper for <code>BTree</code> which implements <code>SortedMap</code> interface
 *
 * @param <K> key type
 * @param <V> value type
 *
 * @author Jan Kotek
 */
class BTreeSortedMap<K, V> extends AbstractMap<K, V> implements SortedMap<K, V> {

    protected final BTree<K, V> tree;

    protected final K fromKey;

    protected final K toKey;

    protected final boolean readonly;

    public BTreeSortedMap(BTree<K, V> tree, boolean readonly) {
        this(tree, readonly, null, null);
    }

    protected BTreeSortedMap(BTree<K, V> tree, boolean readonly, K fromKey, K toKey) {
        this.tree = tree;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.readonly = readonly;
    }

    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet() {
        return _entrySet;
    }

    private final Set<java.util.Map.Entry<K, V>> _entrySet = new AbstractSet<Entry<K, V>>() {

        protected Entry<K, V> newEntry(K k, V v) {
            return new SimpleEntry<K, V>(k, v) {
                private static final long serialVersionUID = 978651696969194154L;

                public V setValue(V arg0) {
                    BTreeSortedMap.this.put(getKey(), arg0);
                    return super.setValue(arg0);
                }

            };
        }

        public boolean add(java.util.Map.Entry<K, V> e) {
            if (readonly)
                throw new UnsupportedOperationException("readonly");

            try {
                if (e.getKey() == null)
                    throw new NullPointerException("Can not add null key");
                if (!inBounds(e.getKey()))
                    throw new IllegalArgumentException("key outside of bounds");
                return tree.insert(e.getKey(), e.getValue(), true) == null;
            } catch (IOException e1) {
                throw new IOError(e1);
            }
        }

        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {

            if (o instanceof Entry) {
                Entry<K, V> e = (java.util.Map.Entry<K, V>) o;
                try {
                    if (!inBounds(e.getKey()))
                        return false;
                    if (e.getKey() != null && tree.get(e.getKey()) != null)
                        return true;
                } catch (IOException e1) {
                    throw new IOError(e1);
                }
            }
            return false;
        }


        public Iterator<java.util.Map.Entry<K, V>> iterator() {
            try {
                final BTree.BTreeTupleBrowser<K, V> br = fromKey == null ?
                        tree.browse() : tree.browse(fromKey);
                return new Iterator<Entry<K, V>>() {

                    private Entry<K, V> next;
                    private K lastKey;

                    void ensureNext() {
                        try {
                            BTree.BTreeTuple<K, V> t = new BTree.BTreeTuple<K, V>();
                            if (br.getNext(t) && inBounds(t.key))
                                next = newEntry(t.key, t.value);
                            else
                                next = null;
                        } catch (IOException e1) {
                            throw new IOError(e1);
                        }
                    }

                    {
                        ensureNext();
                    }


                    public boolean hasNext() {
                        return next != null;
                    }

                    public java.util.Map.Entry<K, V> next() {
                        if (next == null)
                            throw new NoSuchElementException();
                        Entry<K, V> ret = next;
                        lastKey = ret.getKey();
                        //move to next position
                        ensureNext();
                        return ret;
                    }

                    public void remove() {
                        if (readonly)
                            throw new UnsupportedOperationException("readonly");

                        if (lastKey == null)
                            throw new IllegalStateException();
                        try {
                            br.remove(lastKey);
                            lastKey = null;
                        } catch (IOException e1) {
                            throw new IOError(e1);
                        }

                    }
                };

            } catch (IOException e) {
                throw new IOError(e);
            }

        }

        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            if (readonly)
                throw new UnsupportedOperationException("readonly");

            if (o instanceof Entry) {
                Entry<K, V> e = (java.util.Map.Entry<K, V>) o;
                try {
                    //check for nulls
                    if (e.getKey() == null || e.getValue() == null)
                        return false;
                    if (!inBounds(e.getKey()))
                        throw new IllegalArgumentException("out of bounds");
                    //get old value, must be same as item in entry
                    V v = get(e.getKey());
                    if (v == null || !e.getValue().equals(v))
                        return false;
                    V v2 = tree.remove(e.getKey());
                    return v2 != null;
                } catch (IOException e1) {
                    throw new IOError(e1);
                }
            }
            return false;

        }

        public int size() {
            return BTreeSortedMap.this.size();
        }

    };


    public boolean inBounds(K e) {
        Comparator comp = comparator();
        if (comp == null) comp = Utils.COMPARABLE_COMPARATOR;
        if (fromKey != null && comp.compare(e, fromKey) < 0)
            return false;
        if (toKey != null && comp.compare(e, toKey) >= 0)
            return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(Object key) {
        try {
            if (key == null)
                return null;
            if (!inBounds((K) key))
                return null;
            return tree.get((K) key);
        } catch (ClassCastException e) {
            return null;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V remove(Object key) {
        if (readonly)
            throw new UnsupportedOperationException("readonly");

        try {
            if (key == null || tree.get((K) key) == null)
                return null;
            if (!inBounds((K) key))
                throw new IllegalArgumentException("out of bounds");

            return tree.remove((K) key);
        } catch (ClassCastException e) {
            return null;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public V put(K key, V value) {
        if (readonly)
            throw new UnsupportedOperationException("readonly");

        try {
            if (key == null || value == null)
                throw new NullPointerException("Null key or value");
            if (!inBounds(key))
                throw new IllegalArgumentException("out of bounds");
            return tree.insert(key, value, true);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    ;

    @SuppressWarnings("unchecked")
    @Override
    public boolean containsKey(Object key) {
        if (key == null)
            return false;
        try {
            if (!inBounds((K) key))
                return false;
            V v = tree.get((K) key);
            return v != null;
        } catch (IOException e) {
            throw new IOError(e);
        } catch (ClassCastException e) {
            return false;
        }
    }

    public Comparator<? super K> comparator() {
        return tree._comparator;
    }

    public K firstKey() {
        if (size() == 0)
            throw new NoSuchElementException();
        try {
            BTree.BTreeTupleBrowser<K, V> b = fromKey == null ? tree.browse() : tree.browse(fromKey);
            BTree.BTreeTuple<K, V> t = new BTree.BTreeTuple<K, V>();
            b.getNext(t);
            return t.key;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public K lastKey() {
        if (size() == 0)
            throw new NoSuchElementException();
        try {
            BTree.BTreeTupleBrowser<K, V> b = toKey == null ? tree.browse(null) : tree.browse(toKey);
            BTree.BTreeTuple<K, V> t = new BTree.BTreeTuple<K, V>();
            b.getPrevious(t);
            return t.key;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }


    public SortedMap<K, V> headMap(K toKey) {
        return new BTreeSortedMap<K, V>(tree, readonly, null, toKey);
    }


    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        Comparator comp = comparator();
        if (comp == null) comp = Utils.COMPARABLE_COMPARATOR;
        if (comp.compare(fromKey, toKey) > 0)
            throw new IllegalArgumentException("fromKey is bigger then toKey");
        return new BTreeSortedMap<K, V>(tree, readonly, fromKey, toKey);
    }

    public SortedMap<K, V> tailMap(K fromKey) {
        return new BTreeSortedMap<K, V>(tree, readonly, fromKey, null);
    }

    public BTree<K, V> getTree() {
        return tree;
    }


    public void addRecordListener(RecordListener<K, V> listener) {
        tree.addRecordListener(listener);
    }

    public DBAbstract getRecordManager() {
        return tree.getRecordManager();
    }

    public void removeRecordListener(RecordListener<K, V> listener) {
        tree.removeRecordListener(listener);
    }

    public Integer newIntegerKey() {
        if (isEmpty())
            return Integer.valueOf(0);
        K k = lastKey();
        return Integer.valueOf(((Integer) k).intValue() + 1);
    }

    public Long newLongKey() {
        if (isEmpty())
            return Long.valueOf(0);
        K k = lastKey();
        return Long.valueOf(((Long) k).longValue() + 1L);
    }

    public int size() {
        if (fromKey == null && toKey == null)
            return tree.size(); //use fast counter on tree if Map has no bounds
        else {
            //had to count items in iterator
            Iterator iter = keySet().iterator();
            int counter = 0;
            while (iter.hasNext()) {
                iter.next();
                counter++;
            }
            return counter;
        }

    }


}
