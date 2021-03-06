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

import java.io.IOException;
import java.util.SortedMap;

public class BTreeSortedMapTest extends SortedMapInterfaceTest<Integer, String> {

    public BTreeSortedMapTest(String name) {
        super(name, false, false, true, true, true, true);
    }

    DBAbstract r;

    public void setUp() throws Exception {
        r = TestCaseWithTestFile.newBaseRecordManager();
    }

    @Override
    protected Integer getKeyNotInPopulatedMap() throws UnsupportedOperationException {
        return -100;
    }

    @Override
    protected String getValueNotInPopulatedMap() throws UnsupportedOperationException {
        return "XYZ";
    }

    @Override
    protected SortedMap<Integer, String> makeEmptyMap() throws UnsupportedOperationException {
        try {
            BTree<Integer, String> b = BTree.createInstance(r);
            return new BTreeSortedMap<Integer, String>(b, false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected SortedMap<Integer, String> makePopulatedMap() throws UnsupportedOperationException {
        SortedMap<Integer, String> map = makeEmptyMap();
        for (int i = 0; i < 100; i++)
            map.put(i, "aa" + i);
        return map;
    }

}
