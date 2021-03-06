/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.backend.hadoop.executionengine.tez.plan.operator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.pig.LoadFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.JobControlCompiler;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.backend.hadoop.executionengine.tez.runtime.TezInput;
import org.apache.pig.backend.hadoop.executionengine.tez.runtime.TezTaskConfigurable;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigImplConstants;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.tools.pigstats.mapreduce.MRPigStatsUtil;
import org.apache.tez.common.counters.CounterGroup;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.lib.MRReader;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.api.ProcessorContext;
import org.apache.tez.runtime.library.api.KeyValueReader;

/**
 * POSimpleTezLoad is used on the backend to read tuples from a Tez MRInput
 */
public class POSimpleTezLoad extends POLoad implements TezInput, TezTaskConfigurable {

    private static final long serialVersionUID = 1L;

    private String inputKey;

    private transient ProcessorContext processorContext;
    private transient MRInput input;
    private transient KeyValueReader reader;
    private transient Configuration conf;
    private transient boolean finished = false;
    private transient TezCounter inputRecordCounter;
    private transient boolean initialized;
    private transient boolean noTupleCopy;

    public POSimpleTezLoad(OperatorKey k, LoadFunc loader) {
        super(k, loader);
    }

    @Override
    public String[] getTezInputs() {
        return new String[] { inputKey };
    }

    @Override
    public void replaceInput(String oldInputKey, String newInputKey) {
        if (oldInputKey.equals(inputKey)) {
            inputKey = newInputKey;
        }
    }

    @Override
    public void initialize(ProcessorContext processorContext)
            throws ExecException {
        this.processorContext = processorContext;
    }

    @Override
    public void addInputsToSkip(Set<String> inputsToSkip) {
    }

    @Override
    public void attachInputs(Map<String, LogicalInput> inputs,
            Configuration conf)
            throws ExecException {
        this.conf = conf;
        LogicalInput logInput = inputs.get(inputKey);
        if (logInput == null || !(logInput instanceof MRInput)) {
            throw new ExecException("POSimpleTezLoad only accepts MRInputs");
        }
        input = (MRInput) logInput;
        try {
            reader = input.getReader();
            // Set split index, MergeCoGroup need it. And this input is the only input of the
            // MergeCoGroup vertex.
            if (reader instanceof MRReader) {
                int splitIndex = ((PigSplit)((MRReader)reader).getSplit()).getSplitIndex();
                PigMapReduce.sJobContext.getConfiguration().setInt(PigImplConstants.PIG_SPLIT_INDEX, splitIndex);
            }
        } catch (IOException e) {
            throw new ExecException(e);
        }

        // Multiple inputs - other broadcast input like replicate join table, order by sample.
        // We use multi input counters to just get MRInput records count.
        if (inputs.size() > 1) {
            CounterGroup multiInputGroup = processorContext.getCounters()
                    .getGroup(MRPigStatsUtil.MULTI_INPUTS_COUNTER_GROUP);
            if (multiInputGroup == null) {
                processorContext.getCounters().addGroup(
                        MRPigStatsUtil.MULTI_INPUTS_COUNTER_GROUP,
                        MRPigStatsUtil.MULTI_INPUTS_COUNTER_GROUP);
            }
            String name = MRPigStatsUtil.getMultiInputsCounterName(super.getLFile().getFileName(), 0);
            if (name != null) {
                inputRecordCounter = multiInputGroup.addCounter(name, name, 0);
            }
        }
    }

    /**
     * Previously, we reused the same Result object for all results, but we found
     * certain operators (e.g. POStream) save references to the Result object and
     * expect it to be constant.
     */
    @Override
    public Result getNextTuple() throws ExecException {
        try {
            if (finished) {
                return RESULT_EOP;
            }
            if (!reader.next()) {
                // For certain operators (such as STREAM), we could still have some work
                // to do even after seeing the last input. These operators set a flag that
                // says all input has been sent and to run the pipeline one more time.
                if (Boolean.valueOf(conf.get(JobControlCompiler.END_OF_INP_IN_MAP, "false"))) {
                    this.parentPlan.endOfAllInput = true;
                }
                finished = true;
                return RESULT_EOP;
            } else {
                Result res = new Result();
                Tuple next = (Tuple) reader.getCurrentValue();
                if (!initialized) {
                    noTupleCopy = mTupleFactory.newTuple(1).getClass().isInstance(next);
                    initialized = true;
                }
                // Some Loaders return implementations of DefaultTuple instead of BinSedesTuple
                // In that case copy to BinSedesTuple
                res.result = noTupleCopy ? next : mTupleFactory.newTupleNoCopy(next.getAll());
                res.returnStatus = POStatus.STATUS_OK;
                if (inputRecordCounter != null) {
                    inputRecordCounter.increment(1);
                }
                return res;
            }
        } catch (IOException e) {
            throw new ExecException(e);
        }
    }

    public void setInputKey(String inputKey) {
        this.inputKey = inputKey;
    }
}
