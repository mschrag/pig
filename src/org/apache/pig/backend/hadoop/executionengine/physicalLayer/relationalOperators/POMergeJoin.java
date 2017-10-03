/*
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
package org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.pig.FuncSpec;
import org.apache.pig.IndexableLoadFunc;
import org.apache.pig.LoadFunc;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.data.DataType;
import org.apache.pig.data.SchemaTuple;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.SchemaTupleClassGenerator.GenContext;
import org.apache.pig.data.SchemaTupleFactory;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleMaker;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.builtin.DefaultIndexableLoader;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.MultiMap;
import org.apache.pig.newplan.logical.relational.LOJoin;
import org.apache.pig.newplan.logical.relational.LOJoin.JOINTYPE;

/** This operator implements merge join algorithm to do map side joins.
 *  Currently, only two-way joins are supported. One input of join is identified as left
 *  and other is identified as right. Left input tuples are the input records in map.
 *  Right tuples are read from HDFS by opening right stream.
 *
 *    This join doesn't support outer join.
 *    Data is assumed to be sorted in ascending order. It will fail if data is sorted in descending order.
 */

public class POMergeJoin extends PhysicalOperator {
    private static final Log log = LogFactory.getLog(POMergeJoin.class);

    private static final long serialVersionUID = 1L;

    private static final String keyOrderReminder = "Remember that you should " +
        "not change the order of keys before a merge join in a FOREACH or " +
        "manipulate join keys in a UDF in a way that would change the sort " +
        "order. UDFs in a FOREACH are allowed as long as they do not change" +
        "the join key values in a way that would change the sort order.\n";

    // flag to indicate when getNext() is called first.
    private boolean firstTime = true;

    //The Local Rearrange operators modeling the join key
    private POLocalRearrange[] LRs;

    protected transient LoadFunc rightLoader;
    private OperatorKey opKey;

    private transient Object prevLeftKey;

    private transient Result prevLeftInp;

    private transient Object prevRightKey = null;

    private transient Result prevRightInp;

    //boolean denoting whether we are generating joined tuples in this getNext() call or do we need to read in more data.
    private transient boolean doingJoin;

    protected FuncSpec rightLoaderFuncSpec;

    private String rightInputFileName;

    private String indexFile;

    // Buffer to hold accumulated left tuples.
    private transient TuplesToSchemaTupleList leftTuples;

    private MultiMap<PhysicalOperator, PhysicalPlan> inpPlans;

    private PhysicalOperator rightPipelineLeaf;

    private PhysicalOperator rightPipelineRoot;

    private boolean noInnerPlanOnRightSide;

    private transient Object curJoinKey;

    private transient Tuple curJoiningRightTup;

    private int counter; // # of tuples on left side with same key.

    private transient int leftTupSize;

    private transient int rightTupSize;

    private static int ARRAY_LIST_SIZE = 1024;

    private LOJoin.JOINTYPE joinType;

    private String signature;

    private byte endOfRecordMark = POStatus.STATUS_NULL;

    // Only for Spark
    // If current operator reaches at its end, flag endOfInput is set as true.
    // The old flag parentPlan.endOfAllInput doesn't work in spark mode, because it is shared
    // between operators in the same plan, so it could be set by preceding operators even
    // current operator does not reach at its end. (see PIG-4876)
    private transient boolean endOfInput = false;
    public boolean isEndOfInput() {
        return endOfInput;
    }
    public void setEndOfInput (boolean isEndOfInput) {
        endOfInput = isEndOfInput;
    }

    // Only for spark.
    // it means that current operator reaches at its end and the last left input was
    // added into 'leftTuples', ready for join.
    private transient boolean leftInputConsumedInSpark = false;

    // This serves as the default TupleFactory

    /**
     * These TupleFactories are used for more efficient Tuple generation. This should
     * decrease the amount of memory needed for a given map task to successfully perform
     * a merge join.
     */
    private transient TupleMaker mergedTupleMaker;
    private transient TupleMaker leftTupleMaker;

    private Schema leftInputSchema;
    private Schema mergedInputSchema;

    /**
     * @param k
     * @param rp
     * @param inp
     * @param inpPlans there can only be 2 inputs each being a List<PhysicalPlan>
     * Ex. join A by ($0,$1), B by ($1,$2);
     */
    public POMergeJoin(OperatorKey k, int rp, List<PhysicalOperator> inp, MultiMap<PhysicalOperator, PhysicalPlan> inpPlans,
            List<List<Byte>> keyTypes, LOJoin.JOINTYPE joinType, Schema leftInputSchema, Schema rightInputSchema, Schema mergedInputSchema) throws PlanException{

        super(k, rp, inp);
        this.opKey = k;
        this.doingJoin = false;
        this.inpPlans = inpPlans;
        LRs = new POLocalRearrange[2];
        this.createJoinPlans(inpPlans,keyTypes);
        this.indexFile = null;
        this.joinType = joinType;
        this.leftInputSchema = leftInputSchema;
        this.mergedInputSchema = mergedInputSchema;
    }

    public POMergeJoin(POMergeJoin copy) {
        super(copy);
        this.firstTime = copy.firstTime;
        this.LRs = copy.LRs;
        this.rightLoaderFuncSpec = copy.rightLoaderFuncSpec;
        this.rightInputFileName = copy.rightInputFileName;
        this.indexFile = copy.indexFile;
        this.inpPlans = copy.inpPlans;
        this.rightPipelineLeaf = copy.rightPipelineLeaf;
        this.rightPipelineRoot = copy.rightPipelineRoot;
        this.noInnerPlanOnRightSide = copy.noInnerPlanOnRightSide;
        this.counter = copy.counter;
        this.joinType = copy.joinType;
        this.signature = copy.signature;
        this.endOfRecordMark = copy.endOfRecordMark;
        this.leftInputSchema = copy.leftInputSchema;
        this.mergedInputSchema = copy.mergedInputSchema;
    }

    /**
     * Configures the Local Rearrange operators to get keys out of tuple.
     * @throws ExecException
     */
    private void createJoinPlans(MultiMap<PhysicalOperator, PhysicalPlan> inpPlans, List<List<Byte>> keyTypes) throws PlanException{

        int i=-1;
        for (PhysicalOperator inpPhyOp : inpPlans.keySet()) {
            ++i;
            POLocalRearrange lr = new POLocalRearrange(genKey());
            try {
                lr.setIndex(i);
            } catch (ExecException e) {
                throw new PlanException(e.getMessage(),e.getErrorCode(),e.getErrorSource(),e);
            }
            lr.setResultType(DataType.TUPLE);
            lr.setKeyType(keyTypes.get(i).size() > 1 ? DataType.TUPLE : keyTypes.get(i).get(0));
            lr.setPlans(inpPlans.get(inpPhyOp));
            LRs[i]= lr;
        }
    }

    /**
     * This is a helper method that sets up all of the TupleFactory members.
     */
    private void prepareTupleFactories() {
        if (leftInputSchema != null) {
            leftTupleMaker = SchemaTupleBackend.newSchemaTupleFactory(leftInputSchema, false, GenContext.MERGE_JOIN);
        }
        if (leftTupleMaker == null) {
            log.debug("No SchemaTupleFactory available for combined left merge join schema: " + leftInputSchema);
            leftTupleMaker = mTupleFactory;
        } else {
            log.debug("Using SchemaTupleFactory for left merge join schema: " + leftInputSchema);
        }

        if (mergedInputSchema != null) {
            mergedTupleMaker = SchemaTupleBackend.newSchemaTupleFactory(mergedInputSchema, false, GenContext.MERGE_JOIN);
        }
        if (mergedTupleMaker == null) {
            log.debug("No SchemaTupleFactory available for combined left/right merge join schema: " + mergedInputSchema);
            mergedTupleMaker = mTupleFactory;
        } else {
            log.debug("Using SchemaTupleFactory for left/right merge join schema: " + mergedInputSchema);
        }

    }

    /**
     * This provides a List to store Tuples in. The implementation of that list depends on whether
     * or not there is a TupleFactory available.
     * @return the list object to store Tuples in
     */
    private TuplesToSchemaTupleList newLeftTupleArray() {
        return new TuplesToSchemaTupleList(ARRAY_LIST_SIZE, leftTupleMaker);
    }

    /**
     * This is a class that extends ArrayList, making it easy to provide on the fly conversion
     * from Tuple to SchemaTuple. This is necessary because we are not getting SchemaTuples
     * from the source, though in the future that is what we would like to do.
     */
    public static class TuplesToSchemaTupleList extends ArrayList<Tuple> {
        private SchemaTupleFactory tf;

        public TuplesToSchemaTupleList(int ct, TupleMaker<?> tf) {
            super(ct);
            if (tf instanceof SchemaTupleFactory) {
                this.tf = (SchemaTupleFactory)tf;
            }
        }

        public static SchemaTuple<?> convert(Tuple t, SchemaTupleFactory tf) {
            if (t instanceof SchemaTuple<?>) {
                return (SchemaTuple<?>)t;
            }
            SchemaTuple<?> st = tf.newTuple();
            try {
                return st.set(t);
            } catch (ExecException e) {
                throw new RuntimeException("Unable to set SchemaTuple with schema ["
                        + st.getSchemaString() + "] with given Tuple in merge join.");
            }
        }

        @Override
        public boolean add(Tuple t) {
            if (tf != null) {
                t = convert(t, tf);
            }
            return super.add(t);
        }

        @Override
        public Tuple get(int i) {
            return super.get(i);
        }

        @Override
        public int size() {
            return super.size();
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public Result getNextTuple() throws ExecException {

        Object curLeftKey;
        Result curLeftInp;

        if(firstTime){
            prepareTupleFactories();
            leftTuples = newLeftTupleArray();

            // Do initial setup.
            curLeftInp = processInput();
            if(curLeftInp.returnStatus != POStatus.STATUS_OK)
                return curLeftInp;       // Return because we want to fetch next left tuple.

            curLeftKey = extractKeysFromTuple(curLeftInp, 0);
            if(null == curLeftKey) // We drop the tuples which have null keys.
                return new Result(endOfRecordMark, null);

            try {
                seekInRightStream(curLeftKey);
            } catch (IOException e) {
                throwProcessingException(true, e);
            } catch (ClassCastException e) {
                throwProcessingException(true, e);
            }
            leftTuples.add((Tuple)curLeftInp.result);
            firstTime = false;
            prevLeftKey = curLeftKey;
            return new Result(endOfRecordMark, null);
        }

        if(doingJoin){
            // We matched on keys. Time to do the join.

            if(counter > 0){    // We have left tuples to join with current right tuple.
                Tuple joiningLeftTup = leftTuples.get(--counter);
                leftTupSize = joiningLeftTup.size();
                Tuple joinedTup = mergedTupleMaker.newTuple(leftTupSize + rightTupSize);

                for(int i=0; i<leftTupSize; i++) {
                    joinedTup.set(i, joiningLeftTup.get(i));
                }

                for(int i=0; i < rightTupSize; i++) {
                    joinedTup.set(i+leftTupSize, curJoiningRightTup.get(i));
                }

                return new Result(POStatus.STATUS_OK, joinedTup);
            }
            // Join with current right input has ended. But bag of left tuples
            // may still join with next right tuple.

            doingJoin = false;

            while(true){
                Result rightInp = getNextRightInp();
                if(rightInp.returnStatus != POStatus.STATUS_OK){
                    prevRightInp = null;
                    return rightInp;
                }
                else{
                    Object rightKey = extractKeysFromTuple(rightInp, 1);
                    if(null == rightKey) // If we see tuple having null keys in stream, we drop them
                        continue;       // and fetch next tuple.

                    int cmpval = ((Comparable)rightKey).compareTo(curJoinKey);
                    if (cmpval == 0){
                        // Matched the very next right tuple.
                        curJoiningRightTup = (Tuple)rightInp.result;
                        rightTupSize = curJoiningRightTup.size();
                        counter = leftTuples.size();
                        doingJoin = true;
                        return this.getNextTuple();

                    }
                    else if(cmpval > 0){    // We got ahead on right side. Store currently read right tuple.
                        if(!(this.parentPlan.endOfAllInput|| leftInputConsumedInSpark)){
                            prevRightKey = rightKey;
                            prevRightInp = rightInp;
                            // There cant be any more join on this key.
                            leftTuples = newLeftTupleArray();

                            leftTuples.add((Tuple)prevLeftInp.result);
                            return new Result(endOfRecordMark, null);
                        }

                        else{           // This is end of all input and this is last join output.
                            // Right loader in this case wouldn't get a chance to close input stream. So, we close it ourself.
                            try {
                                ((IndexableLoadFunc)rightLoader).close();
                            } catch (IOException e) {
                                // Non-fatal error. We can continue.
                                log.error("Received exception while trying to close right side file: " + e.getMessage());
                            }
                            return new Result(POStatus.STATUS_EOP, null);
                        }
                    }
                    else{   // At this point right side can't be behind.
                        int errCode = 1102;
                        String errMsg = "Data is not sorted on right side. \n" +
                            keyOrderReminder +
                            "Last two tuples encountered were: \n"+
                        curJoiningRightTup+ "\n" + (Tuple)rightInp.result ;
                        throw new ExecException(errMsg,errCode);
                    }
                }
            }
        }

        curLeftInp = processInput();
        switch(curLeftInp.returnStatus){

        case POStatus.STATUS_OK:
            curLeftKey = extractKeysFromTuple(curLeftInp, 0);
            if(null == curLeftKey) // We drop the tuples which have null keys.
                return new Result(endOfRecordMark, null);

            int cmpVal = ((Comparable)curLeftKey).compareTo(prevLeftKey);
            if(cmpVal == 0){
                // Keep on accumulating.
                leftTuples.add((Tuple)curLeftInp.result);
                return new Result(endOfRecordMark, null);
            }
            else if(cmpVal > 0){ // Filled with left bag. Move on.
                curJoinKey = prevLeftKey;
                break;
            }
            else{   // Current key < Prev Key
                int errCode = 1102;
                String errMsg = "Data is not sorted on left side. \n" +
                            keyOrderReminder +
                            "Last two tuples encountered were: \n" +
                prevLeftKey+ "\n" + curLeftKey ;
                throw new ExecException(errMsg,errCode);
            }

        case POStatus.STATUS_EOP:
            if(this.parentPlan.endOfAllInput || isEndOfInput()){
                // We hit the end on left input.
                // Tuples in bag may still possibly join with right side.
                curJoinKey = prevLeftKey;
                curLeftKey = null;
                if (isEndOfInput()) {
                    leftInputConsumedInSpark = true;
                }
                break;
            }
            else    // Fetch next left input.
                return curLeftInp;

        default:    // If encountered with ERR / NULL on left side, we send it down.
            return curLeftInp;
        }

        if((null != prevRightKey)
                && !(this.parentPlan.endOfAllInput || leftInputConsumedInSpark)
                && ((Comparable)prevRightKey).compareTo(curLeftKey) >= 0){

            // This will happen when we accumulated inputs on left side and moved on, but are still behind the right side
            // In that case, throw away the tuples accumulated till now and add the one we read in this function call.
            leftTuples = newLeftTupleArray();
            leftTuples.add((Tuple)curLeftInp.result);
            prevLeftInp = curLeftInp;
            prevLeftKey = curLeftKey;
            return new Result(endOfRecordMark, null);
        }

        // Accumulated tuples with same key on left side.
        // But since we are reading ahead we still haven't checked the read ahead right tuple.
        // Accumulated left tuples may potentially join with that. So, lets check that first.

        if((null != prevRightKey) && prevRightKey.equals(prevLeftKey)){

            curJoiningRightTup = (Tuple)prevRightInp.result;
            counter = leftTuples.size();
            rightTupSize = curJoiningRightTup.size();
            doingJoin = true;
            prevLeftInp = curLeftInp;
            prevLeftKey = curLeftKey;
            return this.getNextTuple();
        }

        // We will get here only when curLeftKey > prevRightKey
        boolean slidingToNextRecord = false;
        while(true){
            // Start moving on right stream to find the tuple whose key is same as with current left bag key.
            Result rightInp;
            if (slidingToNextRecord) {
                rightInp = getNextRightInp();
                slidingToNextRecord = false;
            } else
                rightInp = getNextRightInp(prevLeftKey);

            if(rightInp.returnStatus != POStatus.STATUS_OK)
                return rightInp;

            Object extractedRightKey = extractKeysFromTuple(rightInp, 1);

            if(null == extractedRightKey) // If we see tuple having null keys in stream, we drop them
                continue;       // and fetch next tuple.

            Comparable rightKey = (Comparable)extractedRightKey;

            if( prevRightKey != null && rightKey.compareTo(prevRightKey) < 0){
                // Sanity check.
                int errCode = 1102;
                String errMsg = "Data is not sorted on right side. \n" +
                            keyOrderReminder +
                            "Last two tuples encountered were: \n"+
                prevRightKey+ "\n" + rightKey ;
                throw new ExecException(errMsg,errCode);
            }

            int cmpval = rightKey.compareTo(prevLeftKey);
            if(cmpval < 0) {    // still behind the left side, do nothing, fetch next right tuple.
                slidingToNextRecord = true;
                continue;
            }

            else if (cmpval == 0){  // Found matching tuple. Time to do join.

                curJoiningRightTup = (Tuple)rightInp.result;
                counter = leftTuples.size();
                rightTupSize = curJoiningRightTup.size();
                doingJoin = true;
                prevLeftInp = curLeftInp;
                prevLeftKey = curLeftKey;
                return this.getNextTuple();
            }

            else{    // We got ahead on right side. Store currently read right tuple.
                prevRightKey = rightKey;
                prevRightInp = rightInp;
                // Since we didn't find any matching right tuple we throw away the buffered left tuples and add the one read in this function call.
                leftTuples = newLeftTupleArray();
                leftTuples.add((Tuple)curLeftInp.result);
                prevLeftInp = curLeftInp;
                prevLeftKey = curLeftKey;
                if(this.parentPlan.endOfAllInput || leftInputConsumedInSpark){  // This is end of all input and this is last time we will read right input.
                    // Right loader in this case wouldn't get a chance to close input stream. So, we close it ourself.
                    try {
                        ((IndexableLoadFunc)rightLoader).close();
                    } catch (IOException e) {
                     // Non-fatal error. We can continue.
                        log.error("Received exception while trying to close right side file: " + e.getMessage());
                    }
                }
                return new Result(endOfRecordMark, null);
            }
        }
    }

    private void seekInRightStream(Object firstLeftKey) throws IOException {
        rightLoader = getRightLoader();

        // Pass signature of the loader to rightLoader
        // make a copy of the conf to use in calls to rightLoader.
        rightLoader.setUDFContextSignature(signature);
        Job job = new Job(new Configuration(PigMapReduce.sJobConfInternal.get()));
        rightLoader.setLocation(rightInputFileName, job);
        ((IndexableLoadFunc)rightLoader).initialize(job.getConfiguration());
        ((IndexableLoadFunc)rightLoader).seekNear(
                firstLeftKey instanceof Tuple ? (Tuple)firstLeftKey : mTupleFactory.newTuple(firstLeftKey));
    }

    /**
     * Instantiate right loader
     *
     * @return
     * @throws IOException
     * @throws ExecException
     */
    protected LoadFunc getRightLoader() throws ExecException, IOException {
        LoadFunc loader = (LoadFunc) PigContext.instantiateFuncFromSpec(rightLoaderFuncSpec);
        // check if hadoop distributed cache is used
        if (indexFile != null && loader instanceof DefaultIndexableLoader) {
            DefaultIndexableLoader defLoader = (DefaultIndexableLoader) loader;
            defLoader.setIndexFile(indexFile);
        }
        return loader;
    }

    private Result getNextRightInp(Object leftKey) throws ExecException{

        /*
         * Only call seekNear if the merge join is 'merge-sparse'.  DefaultIndexableLoader does not
         * support more than a single call to seekNear per split - so don't call seekNear.
         */
    	if (joinType == LOJoin.JOINTYPE.MERGESPARSE) {
    		try {
    			((IndexableLoadFunc)rightLoader).seekNear(leftKey instanceof Tuple ? (Tuple)leftKey : mTupleFactory.newTuple(leftKey));
    			prevRightKey = null;
    		} catch (IOException e) {
    			throwProcessingException(true, e);
    		}
    	}
    	return this.getNextRightInp();
    }

    private Result getNextRightInp() throws ExecException{

        try {
            if(noInnerPlanOnRightSide){
                Tuple t = rightLoader.getNext();
                if(t == null) { // no more data on right side
                    return new Result(POStatus.STATUS_EOP, null);
                } else {
                    return new Result(POStatus.STATUS_OK, t);
                }
            } else {
                Result res = rightPipelineLeaf.getNextTuple();
                rightPipelineLeaf.detachInput();
                switch(res.returnStatus){
                case POStatus.STATUS_OK:
                    return res;

                case POStatus.STATUS_EOP:
                    Tuple t = rightLoader.getNext();
                    if(t == null) { // no more data on right side
                        return new Result(POStatus.STATUS_EOP, null);
                    } else {
                        // run the tuple through the pipeline
                        rightPipelineRoot.attachInput(t);
                        return this.getNextRightInp();

                    }
                    default: // We don't deal with ERR/NULL. just pass them down
                        throwProcessingException(false, null);

                }
            }
        } catch (IOException e) {
            throwProcessingException(true, e);
        }
        // we should never get here!
        return new Result(POStatus.STATUS_ERR, null);
    }

    public void throwProcessingException (boolean withCauseException, Exception e) throws ExecException {
        int errCode = 2176;
        String errMsg = "Error processing right input during merge join";
        if(withCauseException) {
            throw new ExecException(errMsg, errCode, PigException.BUG, e);
        } else {
            throw new ExecException(errMsg, errCode, PigException.BUG);
        }
    }

    private Object extractKeysFromTuple(Result inp, int lrIdx) throws ExecException{

        //Separate Key & Value of input using corresponding LR operator
        POLocalRearrange lr = LRs[lrIdx];
        lr.attachInput((Tuple)inp.result);
        Result lrOut = lr.getNextTuple();
        lr.detachInput();
        if(lrOut.returnStatus!=POStatus.STATUS_OK){
            int errCode = 2167;
            String errMsg = "LocalRearrange used to extract keys from tuple isn't configured correctly";
            throw new ExecException(errMsg,errCode,PigException.BUG);
        }

        return ((Tuple) lrOut.result).get(1);
    }

    public void setupRightPipeline(PhysicalPlan rightPipeline) throws FrontendException{

        if(rightPipeline != null){
            if(rightPipeline.getLeaves().size() != 1 || rightPipeline.getRoots().size() != 1){
                int errCode = 2168;
                String errMsg = "Expected physical plan with exactly one root and one leaf.";
                throw new FrontendException(errMsg,errCode,PigException.BUG);
            }

            noInnerPlanOnRightSide = false;
            this.rightPipelineLeaf = rightPipeline.getLeaves().get(0);
            this.rightPipelineRoot = rightPipeline.getRoots().get(0);
            this.rightPipelineRoot.setInputs(null);
        }
        else
            noInnerPlanOnRightSide = true;
    }

    private void readObject(ObjectInputStream is) throws IOException, ClassNotFoundException, ExecException{

        is.defaultReadObject();
    }


    private OperatorKey genKey(){
        return new OperatorKey(opKey.scope,NodeIdGenerator.getGenerator().getNextNodeId(opKey.scope));
    }

    public void setRightLoaderFuncSpec(FuncSpec rightLoaderFuncSpec) {
        this.rightLoaderFuncSpec = rightLoaderFuncSpec;
    }

    public List<PhysicalPlan> getInnerPlansOf(int index) {
        return inpPlans.get(inputs.get(index));
    }

    @Override
    public void visit(PhyPlanVisitor v) throws VisitorException {
        v.visitMergeJoin(this);
    }

    @Override
    public String name() {
        String name = getAliasString() + "MergeJoin";
        if (joinType==LOJoin.JOINTYPE.MERGESPARSE)
            name+="(sparse)";
        name+="[" + DataType.findTypeName(resultType) + "]" + " - " + mKey.toString();
        return name;
    }

    @Override
    public boolean supportsMultipleInputs() {
        return true;
    }

    /* (non-Javadoc)
     * @see org.apache.pig.impl.plan.Operator#supportsMultipleOutputs()
     */
    @Override
    public boolean supportsMultipleOutputs() {
        return false;
    }

    /**
     * @param rightInputFileName the rightInputFileName to set
     */
    public void setRightInputFileName(String rightInputFileName) {
        this.rightInputFileName = rightInputFileName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public void setIndexFile(String indexFile) {
        this.indexFile = indexFile;
    }

    public String getIndexFile() {
        return indexFile;
    }

    @Override
    public Tuple illustratorMarkup(Object in, Object out, int eqClassIndex) {
        return null;
    }

    public LOJoin.JOINTYPE getJoinType() {
        return joinType;
    }

    public POLocalRearrange[] getLRs() {
        return LRs;
    }

    @Override
    public POMergeJoin clone() throws CloneNotSupportedException {
        POMergeJoin clone = (POMergeJoin) super.clone();
        clone.LRs = new POLocalRearrange[this.LRs.length];
        for (int i = 0; i < this.LRs.length; i++) {
            clone.LRs[i] = this.LRs[i].clone();
        }
        clone.rightLoaderFuncSpec = this.rightLoaderFuncSpec.clone();
        clone.inpPlans = new MultiMap<PhysicalOperator, PhysicalPlan>();
        for (PhysicalOperator op : this.inpPlans.keySet()) {
            PhysicalOperator cloneOp = op.clone();
            for (PhysicalPlan phyPlan : this.inpPlans.get(op)) {
                clone.inpPlans.put(cloneOp, phyPlan.clone());
            }
        }
        clone.rightPipelineLeaf = this.rightPipelineLeaf.clone();
        clone.rightPipelineRoot = this.rightPipelineRoot.clone();
        clone.leftInputSchema = this.leftInputSchema.clone();
        clone.mergedInputSchema = this.mergedInputSchema.clone();
        return clone;
    }
}
