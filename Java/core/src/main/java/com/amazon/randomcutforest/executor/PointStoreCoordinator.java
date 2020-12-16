/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.randomcutforest.executor;

import static com.amazon.randomcutforest.CommonUtils.checkNotNull;

import java.util.List;
import java.util.Optional;

import com.amazon.randomcutforest.store.IPointStore;
import com.amazon.randomcutforest.store.PointStoreDouble;

public class PointStoreCoordinator extends AbstractUpdateCoordinator<Integer> {

    private final IPointStore<?> store;

    public PointStoreCoordinator(IPointStore<?> store) {
        checkNotNull(store, "store must not be null");
        this.store = store;
    }

    @Override
    public Integer initUpdate(double[] point) {
        return store.add(point);
    }

    @Override
    public void completeUpdate(List<Optional<UpdateReturn<Integer>>> updateResults, Integer updateInput) {
        updateResults.stream().filter(Optional::isPresent).map(Optional::get).forEach(result -> {
            store.incrementRefCount(result.getFirst());
            result.getSecond().ifPresent(store::decrementRefCount);
        });
        store.decrementRefCount(updateInput);
        totalUpdates++;
    }

    public PointStoreDouble getStore() {
        return (PointStoreDouble) store;
    }
}
