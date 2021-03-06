/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.composition;

import org.apache.ignite.ml.composition.bagging.BaggingTest;
import org.apache.ignite.ml.composition.boosting.GDBTrainerTest;
import org.apache.ignite.ml.composition.boosting.convergence.mean.MeanAbsValueConvergenceCheckerTest;
import org.apache.ignite.ml.composition.boosting.convergence.median.MedianOfMedianConvergenceCheckerTest;
import org.apache.ignite.ml.composition.predictionsaggregator.MeanValuePredictionsAggregatorTest;
import org.apache.ignite.ml.composition.predictionsaggregator.OnMajorityPredictionsAggregatorTest;
import org.apache.ignite.ml.composition.predictionsaggregator.WeightedPredictionsAggregatorTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Test suite for all ensemble models tests.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    GDBTrainerTest.class,
    MeanValuePredictionsAggregatorTest.class,
    OnMajorityPredictionsAggregatorTest.class,
    BaggingTest.class,
    StackingTest.class,
    WeightedPredictionsAggregatorTest.class,
    MedianOfMedianConvergenceCheckerTest.class,
    MeanAbsValueConvergenceCheckerTest.class
})
public class CompositionTestSuite {
}
