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

package com.amazon.randomcutforest.parkservices.preprocessor;

import com.amazon.randomcutforest.RandomCutForest;
import com.amazon.randomcutforest.parkservices.AnomalyDescriptor;
import com.amazon.randomcutforest.parkservices.IRCFComputeDescriptor;
import com.amazon.randomcutforest.parkservices.returntypes.TimedRangeVector;
import com.amazon.randomcutforest.returntypes.RangeVector;

public interface IPreprocessor {

    int getShingleSize();

    int getInputLength();

    double[] getLastShingledPoint();

    int getInternalTimeStamp();

    TimedRangeVector invertForecastRange(RangeVector ranges, IRCFComputeDescriptor lastRelevant);

    <P extends AnomalyDescriptor> P preProcess(P current, IRCFComputeDescriptor lastRelevant, RandomCutForest forest);

    <P extends AnomalyDescriptor> P postProcess(P current, IRCFComputeDescriptor lastRelevant, RandomCutForest forest);
}
