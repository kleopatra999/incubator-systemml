/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.compress;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.random.Well1024a;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.lops.MMTSJ.MMTSJType;
import org.apache.sysml.lops.MapMultChain.ChainType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.compress.ColGroup.CompressionType;
import org.apache.sysml.runtime.compress.estim.CompressedSizeEstimator;
import org.apache.sysml.runtime.compress.estim.CompressedSizeInfo;
import org.apache.sysml.runtime.compress.estim.SizeEstimatorFactory;
import org.apache.sysml.runtime.compress.utils.ConverterUtils;
import org.apache.sysml.runtime.compress.utils.LinearAlgebraUtils;
import org.apache.sysml.runtime.controlprogram.caching.CacheBlock;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysml.runtime.controlprogram.parfor.stat.Timing;
import org.apache.sysml.runtime.functionobjects.Builtin;
import org.apache.sysml.runtime.functionobjects.Builtin.BuiltinCode;
import org.apache.sysml.runtime.functionobjects.KahanPlus;
import org.apache.sysml.runtime.functionobjects.KahanPlusSq;
import org.apache.sysml.runtime.functionobjects.Multiply;
import org.apache.sysml.runtime.functionobjects.ReduceAll;
import org.apache.sysml.runtime.functionobjects.ReduceCol;
import org.apache.sysml.runtime.instructions.cp.CM_COV_Object;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.matrix.data.CTableMap;
import org.apache.sysml.runtime.matrix.data.LibMatrixBincell;
import org.apache.sysml.runtime.matrix.data.LibMatrixReorg;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.MatrixValue;
import org.apache.sysml.runtime.matrix.data.RandomMatrixGenerator;
import org.apache.sysml.runtime.matrix.data.SparseBlock;
import org.apache.sysml.runtime.matrix.mapred.IndexedMatrixValue;
import org.apache.sysml.runtime.matrix.operators.AggregateBinaryOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysml.runtime.matrix.operators.BinaryOperator;
import org.apache.sysml.runtime.matrix.operators.CMOperator;
import org.apache.sysml.runtime.matrix.operators.COVOperator;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.matrix.operators.QuaternaryOperator;
import org.apache.sysml.runtime.matrix.operators.ReorgOperator;
import org.apache.sysml.runtime.matrix.operators.ScalarOperator;
import org.apache.sysml.runtime.matrix.operators.UnaryOperator;
import org.apache.sysml.runtime.util.IndexRange;

/**
 * Experimental version of MatrixBlock that allows a compressed internal
 * representation.
 */
public class CompressedMatrixBlock extends MatrixBlock implements Externalizable
{
	private static final long serialVersionUID = 7319972089143154057L;
	
	//internal configuration
	public static final boolean TRANSPOSE_INPUT = true;
	public static final boolean MATERIALIZE_ZEROS = false;
	public static final long MIN_PAR_AGG_THRESHOLD = 16*1024*1024; //16MB
	public static final boolean INVESTIGATE_ESTIMATES = false;
	private static final boolean LDEBUG = false; //local debug flag
	
	private static final Log LOG = LogFactory.getLog(CompressedMatrixBlock.class.getName());
	
	static{
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("org.apache.sysml.runtime.compress")
				  .setLevel((Level) Level.DEBUG);
		}	
	}
	
	protected ArrayList<ColGroup> _colGroups = null;
	protected CompressionStatistics _stats = null;
	
	public CompressedMatrixBlock() {
		super(-1, -1, true);
	}
	
	/**
	 * Main constructor for building a block from scratch.
	 * 
	 * @param rl
	 *            number of rows in the block
	 * @param cl
	 *            number of columns
	 * @param sparse
	 *            true if the UNCOMPRESSED representation of the block should be
	 *            sparse
	 */
	public CompressedMatrixBlock(int rl, int cl, boolean sparse) {
		super(rl, cl, sparse);
	}

	/**
	 * "Copy" constructor to populate this compressed block with the
	 * uncompressed contents of a conventional block. Does <b>not</b> compress
	 * the block.
	 * 
	 * @param mb matrix block
	 */
	public CompressedMatrixBlock(MatrixBlock mb) {
		super(mb.getNumRows(), mb.getNumColumns(), mb.isInSparseFormat());
		
		//shallow copy (deep copy on compression, prevents unnecessary copy) 
		if( isInSparseFormat() )
			sparseBlock = mb.getSparseBlock();
		else
			denseBlock = mb.getDenseBlock();
		nonZeros = mb.getNonZeros();
	}

	/**
	 * Obtain the column groups.
	 * 
	 * @return the column groups constructed by the compression process.
	 * 
	 */
	public ArrayList<ColGroup> getColGroups() {
		return _colGroups;
	}

	/**
	 * Obtain whether this block is in compressed form or not.
	 * 
	 * @return true if this block is in compressed form; false if the block has
	 *         not yet been compressed
	 */
	public boolean isCompressed() {
		return (_colGroups != null);
	}

	public boolean isSingleUncompressedGroup(){
		return (_colGroups!=null && _colGroups.size()==1 
				&& _colGroups.get(0) instanceof ColGroupUncompressed);
	}

	private void allocateColGroupList() {
		_colGroups = new ArrayList<ColGroup>();
	}
	
	@Override
	public boolean isEmptyBlock(boolean safe)  {
		if( !isCompressed() )
			return super.isEmptyBlock(safe);		
		return (_colGroups == null || getNonZeros()==0);
	}
	
	/**
	 * Compress the contents of this matrix block. After compression, the
	 * uncompressed data is discarded. Attempts to update this block after
	 * calling this method currently result in INCORRECT BEHAVIOR, something
	 * which should be fixed if we move ahead with this compression strategy.
	 * 
	 * +per column sparsity
	 * 
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public void compress() 
		throws DMLRuntimeException
	{
		//default sequential execution
		compress(1);
	}
	
	/**
	 * Compress block.
	 * 
	 * @param k  number of threads
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public void compress(int k) 
		throws DMLRuntimeException 
	{
		//check for redundant compression
		if( isCompressed() ){
			throw new DMLRuntimeException("Redundant compression, block already compressed.");
		}

		Timing time = new Timing(true);
		_stats = new CompressionStatistics();
		
		// SAMPLE-BASED DECISIONS:
		// Decisions such as testing if a column is amenable to bitmap
		// compression or evaluating co-coding potentionls are made based on a
		// subset of the rows. For large datasets, sampling might take a
		// significant amount of time. So, we generate only one sample and use
		// it for the entire compression process.

		//prepare basic meta data and deep copy / transpose input
		final int numRows = getNumRows();
		final int numCols = getNumColumns();
		final boolean sparse = isInSparseFormat();
		final double sp = OptimizerUtils.getSparsity(numRows, numCols, getNonZeros());
		MatrixBlock rawblock = !TRANSPOSE_INPUT ? new MatrixBlock(this) :
			LibMatrixReorg.transpose(this, new MatrixBlock(numCols, numRows, sparse), k);
		
		//construct sample-based size estimator
		CompressedSizeEstimator bitmapSizeEstimator = 
				SizeEstimatorFactory.getSizeEstimator(rawblock, numRows);

		// The current implementation of this method is written for correctness,
		// not for performance or for minimal use of temporary space.

		// We start with a full set of columns.
		HashSet<Integer> remainingCols = new HashSet<Integer>();
		for (int i = 0; i < numCols; i++)
			remainingCols.add(i);

		// PHASE 1: Classify columns by compression type
		// We start by determining which columns are amenable to bitmap compression
		double uncompressedColumnSize = getUncompressedSize(numRows, 1, sp);

		// information about the bitmap amenable columns
		List<Integer> bitmapCols = new ArrayList<Integer>();
		List<Integer> uncompressedCols = new ArrayList<Integer>();
		List<Integer> colsCards = new ArrayList<Integer>();
		List<Long> compressedSizes = new ArrayList<Long>();
		HashMap<Integer, Double> compressionRatios = new HashMap<Integer, Double>();
		
		// Classify columns according to ration (size uncompressed / size compressed), 
		// where a column is compressible if ratio > 1.
		CompressedSizeInfo[] sizeInfos = (k > 1) ?
				computeCompressedSizeInfos(bitmapSizeEstimator, numCols, k) : 
				computeCompressedSizeInfos(bitmapSizeEstimator, numCols);		
		for (int col = 0; col < numCols; col++)  {	
			long compressedSize = sizeInfos[col].getMinSize();
			double compRatio = uncompressedColumnSize / compressedSize;			
			if (compRatio > 1) {
				bitmapCols.add(col);
				compressionRatios.put(col, compRatio);
				colsCards.add(sizeInfos[col].getEstCarinality());
				compressedSizes.add(compressedSize);
			}
			else
				uncompressedCols.add(col);
		}

		_stats.timePhase1 = time.stop();
		if( LOG.isDebugEnabled() ) {
			LOG.debug("Compression statistics:");
			LOG.debug("--compression phase 1: "+_stats.timePhase1);
		}

		// PHASE 2: Grouping columns
		// Divide the bitmap columns into column groups.
		List<int[]> bitmapColGrps = PlanningCoCoder.findCocodesByPartitioning(
				bitmapSizeEstimator, bitmapCols, colsCards, compressedSizes, numRows, 
				isInSparseFormat() ? sp : 1, k);

		_stats.timePhase2 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("--compression phase 2: "+_stats.timePhase2);
		
		if( INVESTIGATE_ESTIMATES ) {
			double est = 0;
			for( int[] groupIndices : bitmapColGrps )
				est += bitmapSizeEstimator.estimateCompressedColGroupSize(groupIndices).getMinSize();
			est += uncompressedCols.size() * uncompressedColumnSize;
			_stats.estSize = est;
		}
		
		// PHASE 3: Compress and correct sample-based decisions
		ColGroup[] colGroups = (k > 1) ?
				compressColGroups(rawblock, bitmapSizeEstimator, compressionRatios, numRows, sp, bitmapColGrps, k) : 
				compressColGroups(rawblock, bitmapSizeEstimator, compressionRatios, numRows, sp, bitmapColGrps); 	
		allocateColGroupList();
		for( int j=0; j<colGroups.length; j++ ) {
			if( colGroups[j] != null ) {
				for( int col : colGroups[j].getColIndices() )
					remainingCols.remove(col);
				_colGroups.add(colGroups[j]);
			}
		}
		
		_stats.timePhase3 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("--compression phase 3: "+_stats.timePhase3);
		
		// Phase 4: Cleanup
		// The remaining columns are stored uncompressed as one big column group
		if( !remainingCols.isEmpty() ) {
			ArrayList<Integer> list = new ArrayList<Integer>(remainingCols);
			ColGroupUncompressed ucgroup = new ColGroupUncompressed(list, rawblock);
			_colGroups.add(ucgroup);
		}
		
		_stats.size = estimateCompressedSizeInMemory();
		_stats.ratio= estimateSizeInMemory() / _stats.size;
		
		//final cleanup (discard uncompressed block)
		rawblock.cleanupBlock(true, true);
		this.cleanupBlock(true, true);
		
		_stats.timePhase4 = time.stop();
		if( LOG.isDebugEnabled() ) {
			LOG.debug("--compression phase 4: "+_stats.timePhase4);
			LOG.debug("--num col groups: "+_colGroups.size());
			LOG.debug("--compressed size: "+_stats.size);
			LOG.debug("--compression ratio: "+_stats.ratio);
		}
	}

	public CompressionStatistics getCompressionStatistics() {
		return _stats;
	}

	private static CompressedSizeInfo[] computeCompressedSizeInfos(CompressedSizeEstimator estim, int clen) {
		CompressedSizeInfo[] ret = new CompressedSizeInfo[clen];
		for( int col=0; col<clen; col++ )
			ret[col] = estim.estimateCompressedColGroupSize(new int[] { col });
		return ret;
	}

	private static CompressedSizeInfo[] computeCompressedSizeInfos(CompressedSizeEstimator estim, int clen, int k) 
		throws DMLRuntimeException 
	{	
		try {
			ExecutorService pool = Executors.newFixedThreadPool( k );
			ArrayList<SizeEstimTask> tasks = new ArrayList<SizeEstimTask>();
			for( int col=0; col<clen; col++ )
				tasks.add(new SizeEstimTask(estim, col));
			List<Future<CompressedSizeInfo>> rtask = pool.invokeAll(tasks);	
			ArrayList<CompressedSizeInfo> ret = new ArrayList<CompressedSizeInfo>();
			for( Future<CompressedSizeInfo> lrtask : rtask )
				ret.add(lrtask.get());
			pool.shutdown();
			return ret.toArray(new CompressedSizeInfo[0]);
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	private static ColGroup[] compressColGroups(MatrixBlock in, CompressedSizeEstimator estim, HashMap<Integer, Double> compRatios, int rlen, double sp, List<int[]> groups)
	{
		ColGroup[] ret = new ColGroup[groups.size()];
		for( int i=0; i<groups.size(); i++ )
			ret[i] = compressColGroup(in, estim, compRatios, rlen, sp, groups.get(i));
		
		return ret;
	}

	private static ColGroup[] compressColGroups(MatrixBlock in, CompressedSizeEstimator estim, HashMap<Integer, Double> compRatios, int rlen, double sp, List<int[]> groups, int k) 
		throws DMLRuntimeException
	{
		try {
			ExecutorService pool = Executors.newFixedThreadPool( k );
			ArrayList<CompressTask> tasks = new ArrayList<CompressTask>();
			for( int[] colIndexes : groups )
				tasks.add(new CompressTask(in, estim, compRatios, rlen, sp, colIndexes));
			List<Future<ColGroup>> rtask = pool.invokeAll(tasks);	
			ArrayList<ColGroup> ret = new ArrayList<ColGroup>();
			for( Future<ColGroup> lrtask : rtask )
				ret.add(lrtask.get());
			pool.shutdown();
			return ret.toArray(new ColGroup[0]);
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	private static ColGroup compressColGroup(MatrixBlock in, CompressedSizeEstimator estim, HashMap<Integer, Double> compRatios, int rlen, double sp, int[] colIndexes) 
	{
		int[] allGroupIndices = null;
		int allColsCount = colIndexes.length;
		CompressedSizeInfo sizeInfo;
		// The compression type is decided based on a full bitmap since it
		// will be reused for the actual compression step.
		UncompressedBitmap ubm = null;
		PriorityQueue<CompressedColumn> compRatioPQ = null;
		boolean skipGroup = false;
		while (true) 
		{
			//exact big list and observe compression ratio
			ubm = BitmapEncoder.extractBitmap(colIndexes, in); 
			sizeInfo = estim.estimateCompressedColGroupSize(ubm);	
			double compRatio = getUncompressedSize(rlen, colIndexes.length, sp) / sizeInfo.getMinSize();
			
			if( compRatio > 1 ) {
				break; // we have a good group
			} 
			
			// modify the group
			if (compRatioPQ == null) {
				// first modification
				allGroupIndices = colIndexes.clone();
				compRatioPQ = new PriorityQueue<CompressedMatrixBlock.CompressedColumn>();
				for (int i = 0; i < colIndexes.length; i++)
					compRatioPQ.add(new CompressedColumn(i, compRatios.get(colIndexes[i])));
			}

			// index in allGroupIndices
			int removeIx = compRatioPQ.poll().colIx;
			allGroupIndices[removeIx] = -1;
			allColsCount--;
			if (allColsCount == 0) {
				skipGroup = true;
				break;
			}
			colIndexes = new int[allColsCount];
			// copying the values that do not equal -1
			int ix = 0;
			for(int col : allGroupIndices)
				if (col != -1)
					colIndexes[ix++] = col;
		}

		//add group to uncompressed fallback
		if( skipGroup )
			return null;

		//create compressed column group
		long rleSize = sizeInfo.getRLESize();
		long oleSize = sizeInfo.getOLESize();
		if( rleSize < oleSize )
			return new ColGroupRLE(colIndexes, rlen, ubm);
		else
			return new ColGroupOLE(colIndexes, rlen, ubm);
	}
	
	/**
	 * Compute a conservative estimate of the uncompressed size of a column group.
	 * 
	 * @param rlen row length
	 * @param clen column length
	 * @param sparsity the sparsity
	 * @return estimate of uncompressed size of column group
	 */
	private static double getUncompressedSize(int rlen, int clen, double sparsity) {
		//we estimate the uncompressed size as 8 * nnz in order to cover both
		//sparse and dense with moderate underestimation (which is conservative as 
		//it is biased towards uncompressed columns)
		return 8 * rlen * clen * sparsity;
	}

	/**
	 * Decompress block.
	 * 
	 * @return a new uncompressed matrix block containing the contents of this
	 *         block
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public MatrixBlock decompress() throws DMLRuntimeException 
	{
		//early abort for not yet compressed blocks
		if( !isCompressed() )
			return new MatrixBlock(this); 
		
		Timing time = new Timing(true);
		
		//preallocation sparse rows to avoid repeated reallocations		
		MatrixBlock ret = new MatrixBlock(getNumRows(), getNumColumns(), isInSparseFormat(), getNonZeros());
		if( ret.isInSparseFormat() ) {
			int[] rnnz = new int[rlen];
			for (ColGroup grp : _colGroups)
				grp.countNonZerosPerRow(rnnz, 0, rlen);
			ret.allocateSparseRowsBlock();
			SparseBlock rows = ret.getSparseBlock();
			for( int i=0; i<rlen; i++ )
				rows.allocate(i, rnnz[i]);
		}
		
		//core decompression (append if sparse)
		for (ColGroup grp : _colGroups)
			grp.decompressToBlock(ret, 0, rlen);
		
		//post-processing (for append in decompress)
		ret.setNonZeros(nonZeros);
		if( ret.isInSparseFormat() )
			ret.sortSparseRows();

		if( LOG.isDebugEnabled() )
			LOG.debug("decompressed block in "+time.stop()+"ms.");
		
		return ret;
	}
	
	/**
	 * Decompress block.
	 * 
	 * @param k degree of parallelism
	 * @return a new uncompressed matrix block containing the contents 
	 * of this block
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public MatrixBlock decompress(int k) throws DMLRuntimeException 
	{
		//early abort for not yet compressed blocks
		if( !isCompressed() )
			return new MatrixBlock(this); 
		if( k <= 1 )
			return decompress();
		
		Timing time = new Timing(true);
		
		MatrixBlock ret = new MatrixBlock(rlen, clen, sparse, nonZeros);
		ret.allocateDenseOrSparseBlock();
		
		//multi-threaded decompression
		try {
			ExecutorService pool = Executors.newFixedThreadPool( k );
			int rlen = getNumRows();
			int seqsz = BitmapEncoder.BITMAP_BLOCK_SZ;
			int blklen = (int)(Math.ceil((double)rlen/k));
			blklen += (blklen%seqsz != 0)?seqsz-blklen%seqsz:0;
			ArrayList<DecompressTask> tasks = new ArrayList<DecompressTask>();
			for( int i=0; i<k & i*blklen<getNumRows(); i++ )
				tasks.add(new DecompressTask(_colGroups, ret, i*blklen, Math.min((i+1)*blklen,rlen)));
			List<Future<Object>> rtasks = pool.invokeAll(tasks);	
			pool.shutdown();
			for( Future<Object> rt : rtasks )
				rt.get(); //error handling
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
		
		//post-processing 
		ret.setNonZeros(nonZeros);
		
		if( LOG.isDebugEnabled() )
			LOG.debug("decompressed block w/ k="+k+" in "+time.stop()+"ms.");
		
		return ret;
	}

	/**
	 * Obtain an upper bound on the memory used to store the compressed block.
	 * 
	 * @return an upper bound on the memory used to store this compressed block
	 *         considering class overhead.
	 */
	public long estimateCompressedSizeInMemory() {
		if (!isCompressed())
			return 0;
		// basic data inherited from MatrixBlock
		long total = MatrixBlock.estimateSizeInMemory(0, 0, 0);
		// adding the size of colGroups ArrayList overhead
		// object overhead (32B) + int size (4B) + int modCount (4B) + Object[]
		// elementData overhead + reference (32+8)B +reference ofr each Object (8B)
		total += 80 + 8 * _colGroups.size();
		for (ColGroup grp : _colGroups)
			total += grp.estimateInMemorySize();
		return total;
	}

	private static class CompressedColumn implements Comparable<CompressedColumn> {
		int colIx;
		double compRatio;

		public CompressedColumn(int colIx, double compRatio) {
			this.colIx = colIx;
			this.compRatio = compRatio;
		}

		@Override
		public int compareTo(CompressedColumn o) {
			return (int) Math.signum(compRatio - o.compRatio);
		}
	}
	
	public static class CompressionStatistics {
		public double timePhase1 = -1;
		public double timePhase2 = -1;
		public double timePhase3 = -1;
		public double timePhase4 = -1;
		public double estSize = -1;
		public double size = -1;
		public double ratio = -1;
		
		public CompressionStatistics() {
			//do nothing
		}
	} 

	@Override
	public double quickGetValue(int r, int c) {
		if( !isCompressed() ) {
			return super.quickGetValue(r, c);
		}
		
		//find column group according to col index
		ColGroup grp = null;
		for( ColGroup group : _colGroups )
			if( Arrays.binarySearch(group.getColIndices(), c) >= 0 ) {
				grp = group; break;
			}
		
		//find row value 
		return grp.get(r, c);
	}	
	
	//////////////////////////////////////////
	// Serialization / Deserialization

	@Override
	public long getExactSizeOnDisk() 
	{
		//header information
		long ret = 12;
		
		for( ColGroup grp : _colGroups ) {
			ret += 1; //type info
			ret += grp.getExactSizeOnDisk();
		}
		
		return ret;
	}
	
	@Override
	public void readFields(DataInput in) 
		throws IOException 
	{
		boolean compressed = in.readBoolean();
		
		//deserialize uncompressed block
		if( !compressed ) {
			super.readFields(in);
			return;
		}
		
		//deserialize compressed block
		rlen = in.readInt();
		clen = in.readInt();
		nonZeros = in.readLong();
		int ncolGroups = in.readInt();
		
		_colGroups = new ArrayList<ColGroup>(ncolGroups);
		for( int i=0; i<ncolGroups; i++ ) 
		{
			CompressionType ctype = CompressionType.values()[in.readByte()];
			ColGroup grp = null;
			
			//create instance of column group
			switch( ctype ) {
				case UNCOMPRESSED:
					grp = new ColGroupUncompressed(); break;
				case OLE_BITMAP:
					grp = new ColGroupOLE(); break;
				case RLE_BITMAP:
					grp = new ColGroupRLE(); break;
			}
			
			//deserialize and add column group
			grp.readFields(in);
			_colGroups.add(grp);
		}
	}
	
	@Override
	public void write(DataOutput out) 
		throws IOException 
	{
		out.writeBoolean( isCompressed() );
		
		//serialize uncompressed block
		if( !isCompressed() ) {
			super.write(out);
			return;
		}
		
		//serialize compressed matrix block
		out.writeInt(rlen);
		out.writeInt(clen);
		out.writeLong(nonZeros);
		out.writeInt(_colGroups.size());
		
		for( ColGroup grp : _colGroups ) {
			out.writeByte( grp.getCompType().ordinal() );
			grp.write(out); //delegate serialization
		}
	}
	
	
	/**
	 * Redirects the default java serialization via externalizable to our default 
	 * hadoop writable serialization for efficient broadcast/rdd deserialization. 
	 * 
	 * @param is object input
	 * @throws IOException if IOException occurs
	 */
	@Override
	public void readExternal(ObjectInput is) 
		throws IOException
	{
		readFields(is);
	}
	
	/**
	 * Redirects the default java serialization via externalizable to our default 
	 * hadoop writable serialization for efficient broadcast/rdd serialization. 
	 * 
	 * @param os object output
	 * @throws IOException if IOException occurs
	 */
	@Override
	public void writeExternal(ObjectOutput os) 
		throws IOException
	{
		write(os);	
	}
	
	
	//////////////////////////////////////////
	// Operations (overwrite existing ops for seamless integration)

	@Override
	public MatrixValue scalarOperations(ScalarOperator sop, MatrixValue result) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix scalar if necessary
		if( !isCompressed() ) {
			return super.scalarOperations(sop, result);
		}
		
		//allocate the output matrix block
		CompressedMatrixBlock ret = null;
		if( result==null || !(result instanceof CompressedMatrixBlock) )
			ret = new CompressedMatrixBlock(getNumRows(), getNumColumns(), sparse);
		else {
			ret = (CompressedMatrixBlock) result;
			ret.reset(rlen, clen);
		}
		
		// Apply the operation recursively to each of the column groups.
		// Most implementations will only modify metadata.
		ArrayList<ColGroup> newColGroups = new ArrayList<ColGroup>();
		for (ColGroup grp : _colGroups) {
			newColGroups.add(grp.scalarOperation(sop));
		}
		ret._colGroups = newColGroups;
		ret.setNonZeros(rlen*clen);
		
		return ret;
	}

	@Override
	public MatrixBlock appendOperations(MatrixBlock that, MatrixBlock ret) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix append if necessary
		if( !isCompressed() ) {
			if( that instanceof CompressedMatrixBlock )
				that = ((CompressedMatrixBlock) that).decompress();
			return super.appendOperations(that, ret, true);
		}
		
		final int m = rlen;
		final int n = clen+that.getNumColumns();
		final long nnz = nonZeros+that.getNonZeros();		
		
		//init result matrix 
		CompressedMatrixBlock ret2 = null;
		if( ret == null || !(ret instanceof CompressedMatrixBlock) ) {
			ret2 = new CompressedMatrixBlock(m, n, isInSparseFormat());
		}
		else {
			ret2 = (CompressedMatrixBlock) ret;
			ret2.reset(m, n);
		}
			
		//shallow copy of lhs column groups
		ret2.allocateColGroupList();
		ret2._colGroups.addAll(_colGroups);
		
		//copy of rhs column groups w/ col index shifting
		if( !(that instanceof CompressedMatrixBlock) ) {
			that = new CompressedMatrixBlock(that);
			((CompressedMatrixBlock)that).compress();
		}
		ArrayList<ColGroup> inColGroups = ((CompressedMatrixBlock) that)._colGroups;
		for( ColGroup group : inColGroups ) {
			ColGroup tmp = ConverterUtils.copyColGroup(group);
			tmp.shiftColIndices(clen);
			ret2._colGroups.add(tmp);
		}
		
		//meta data maintenance
		ret2.setNonZeros(nnz);		
		return ret2;
	}
	
	@Override
	public MatrixBlock chainMatrixMultOperations(MatrixBlock v, MatrixBlock w, MatrixBlock out, ChainType ctype) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.chainMatrixMultOperations(v, w, out, ctype);
		}
		
		//single-threaded mmchain of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().chainMatrixMultOperations(v, w, out, ctype);
		}
		
		//Timing time = new Timing(true);
		
		//prepare result
		if( out != null )
			out.reset(clen, 1, false);
		else 
			out = new MatrixBlock(clen, 1, false);
		
		//empty block handling
		if( isEmptyBlock(false) ) 
			return out;
			
		//compute matrix mult
		MatrixBlock tmp = new MatrixBlock(rlen, 1, false);
		rightMultByVector(v, tmp);
		if( ctype == ChainType.XtwXv ) {
			BinaryOperator bop = new BinaryOperator(Multiply.getMultiplyFnObject());
			LibMatrixBincell.bincellOpInPlace(tmp, w, bop);
		}
		leftMultByVectorTranspose(_colGroups, tmp, out, true);
		
		//System.out.println("Compressed MMChain in "+time.stop());
		
		return out;
	}

	@Override
	public MatrixBlock chainMatrixMultOperations(MatrixBlock v, MatrixBlock w, MatrixBlock out, ChainType ctype, int k) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ){
			return super.chainMatrixMultOperations(v, w, out, ctype, k);
		}
		
		//multi-threaded mmchain of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().chainMatrixMultOperations(v, w, out, ctype, k);
		}

		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//prepare result
		if( out != null )
			out.reset(clen, 1, false);
		else 
			out = new MatrixBlock(clen, 1, false);
		
		//empty block handling
		if( isEmptyBlock(false) ) 
			return out;
		
		//compute matrix mult
		MatrixBlock tmp = new MatrixBlock(rlen, 1, false);
		rightMultByVector(v, tmp, k);
		if( ctype == ChainType.XtwXv ) {
			BinaryOperator bop = new BinaryOperator(Multiply.getMultiplyFnObject());
			LibMatrixBincell.bincellOpInPlace(tmp, w, bop);
		}
		leftMultByVectorTranspose(_colGroups, tmp, out, true, k);
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed MMChain k="+k+" in "+time.stop());
		
		return out;
	}
	
	@Override
	public MatrixValue aggregateBinaryOperations(MatrixValue mv1, MatrixValue mv2, MatrixValue result, AggregateBinaryOperator op)
			throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.aggregateBinaryOperations(mv1, mv2, result, op);
		}
	
		//multi-threaded mm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			MatrixBlock tmp = ((ColGroupUncompressed)_colGroups.get(0)).getData();
			return tmp.aggregateBinaryOperations(this==mv1?tmp:mv1, this==mv2?tmp:mv2, result, op);
		}
		
		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//setup meta data (dimensions, sparsity)
		int rl = mv1.getNumRows();
		int cl = mv2.getNumColumns();
		
		//create output matrix block
		MatrixBlock ret = (MatrixBlock) result;
		if( ret==null )
			ret = new MatrixBlock(rl, cl, false, rl*cl);
		else
			ret.reset(rl, cl, false, rl*cl);
		
		//compute matrix mult
		if( mv1.getNumRows()>1 && mv2.getNumColumns()==1 ) { //MV right
			CompressedMatrixBlock cmb = (CompressedMatrixBlock)mv1;
			MatrixBlock mb = (MatrixBlock) mv2;
			if( op.getNumThreads()>1 )
				cmb.rightMultByVector(mb, ret, op.getNumThreads());
			else
				cmb.rightMultByVector(mb, ret);
		}
		else if( mv1.getNumRows()==1 && mv2.getNumColumns()>1 ) { //MV left
			MatrixBlock mb = (MatrixBlock) mv1;
			if( op.getNumThreads()>1 )
				leftMultByVectorTranspose(_colGroups, mb, ret, false, op.getNumThreads());
			else
				leftMultByVectorTranspose(_colGroups, mb, ret, false);
		}
		else {
			//NOTE: we could decompress and invoke super.aggregateBinary but for now
			//we want to have an eager fail if this happens
			throw new DMLRuntimeException("Unsupported matrix-matrix multiplication over compressed matrix block.");
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed MM in "+time.stop());
		
		return ret;
	}
	
	@Override
	public MatrixValue aggregateUnaryOperations(AggregateUnaryOperator op, MatrixValue result, 
			int blockingFactorRow, int blockingFactorCol, MatrixIndexes indexesIn, boolean inCP) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.aggregateUnaryOperations(op, result, blockingFactorRow, blockingFactorCol, indexesIn, inCP);
		}
		
		//check for supported operations
		if( !(op.aggOp.increOp.fn instanceof KahanPlus || op.aggOp.increOp.fn instanceof KahanPlusSq
			 || (op.aggOp.increOp.fn instanceof Builtin && 
				(((Builtin)op.aggOp.increOp.fn).getBuiltinCode()==BuiltinCode.MIN 
				||((Builtin)op.aggOp.increOp.fn).getBuiltinCode()==BuiltinCode.MAX))) ){
			throw new DMLRuntimeException("Unary aggregates other than sum/sumsq/min/max not supported yet.");
		}
		
		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;

		//prepare output dimensions
		CellIndex tempCellIndex = new CellIndex(-1,-1);
		op.indexFn.computeDimension(rlen, clen, tempCellIndex);
		if(op.aggOp.correctionExists) {
			switch(op.aggOp.correctionLocation)
			{
				case LASTROW: tempCellIndex.row++;  break;
				case LASTCOLUMN: tempCellIndex.column++; break;
				case LASTTWOROWS: tempCellIndex.row+=2; break;
				case LASTTWOCOLUMNS: tempCellIndex.column+=2; break;
				default:
					throw new DMLRuntimeException("unrecognized correctionLocation: "+op.aggOp.correctionLocation);	
			}
		}
		
		// initialize and allocate the result
		if(result==null)
			result=new MatrixBlock(tempCellIndex.row, tempCellIndex.column, false);
		else
			result.reset(tempCellIndex.row, tempCellIndex.column, false);
		MatrixBlock ret = (MatrixBlock) result;
		ret.allocateDenseBlock();
		
		//special handling init value for rowmins/rowmax
		if( op.indexFn instanceof ReduceCol && op.aggOp.increOp.fn instanceof Builtin ) {
			double val = Double.MAX_VALUE * ((((Builtin)op.aggOp.increOp.fn).getBuiltinCode()==BuiltinCode.MAX)?-1:1);
			Arrays.fill(ret.getDenseBlock(), val);
		}
		
		//core unary aggregate
		if(    op.getNumThreads() > 1 
			&& getExactSizeOnDisk() > MIN_PAR_AGG_THRESHOLD ) 
		{
			//multi-threaded execution of all groups 
			ArrayList<ColGroup>[] grpParts = createStaticTaskPartitioning(
					(op.indexFn instanceof ReduceCol) ? 1 : op.getNumThreads(), false);
			ColGroupUncompressed uc = getUncompressedColGroup();
			try {
				//compute uncompressed column group in parallel (otherwise bottleneck)
				if( uc != null )
					 ret = (MatrixBlock)uc.getData().aggregateUnaryOperations(op, ret, blockingFactorRow, blockingFactorCol, indexesIn, false);					
				//compute all compressed column groups
				ExecutorService pool = Executors.newFixedThreadPool( op.getNumThreads() );
				ArrayList<UnaryAggregateTask> tasks = new ArrayList<UnaryAggregateTask>();
				if( op.indexFn instanceof ReduceCol && grpParts.length > 0 ) {
					int seqsz = BitmapEncoder.BITMAP_BLOCK_SZ;
					int blklen = (int)(Math.ceil((double)rlen/op.getNumThreads()));
					blklen += (blklen%seqsz != 0)?seqsz-blklen%seqsz:0;
					for( int i=0; i<op.getNumThreads() & i*blklen<rlen; i++ )
						tasks.add(new UnaryAggregateTask(grpParts[0], ret, i*blklen, Math.min((i+1)*blklen,rlen), op));
				}
				else
					for( ArrayList<ColGroup> grp : grpParts )
						tasks.add(new UnaryAggregateTask(grp, ret, 0, rlen, op));
				List<Future<MatrixBlock>> rtasks = pool.invokeAll(tasks);	
				pool.shutdown();
				
				//aggregate partial results
				if( op.indexFn instanceof ReduceAll ) {
					double val = ret.quickGetValue(0, 0);
					for( Future<MatrixBlock> rtask : rtasks )
						val = op.aggOp.increOp.fn.execute(val, 
								rtask.get().quickGetValue(0, 0));
					ret.quickSetValue(0, 0, val);
				}		
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
		}
		else {
			//process UC column group
			for (ColGroup grp : _colGroups)
				if( grp instanceof ColGroupUncompressed )
					grp.unaryAggregateOperations(op, ret);
			
			//process OLE/RLE column groups
			for (ColGroup grp : _colGroups)
				if( !(grp instanceof ColGroupUncompressed) )
					grp.unaryAggregateOperations(op, ret);
		}
		
		//special handling zeros for rowmins/rowmax
		if( op.indexFn instanceof ReduceCol && op.aggOp.increOp.fn instanceof Builtin ) {
			int[] rnnz = new int[rlen];
			for( ColGroup grp : _colGroups )
				grp.countNonZerosPerRow(rnnz, 0, rlen);
			Builtin builtin = (Builtin)op.aggOp.increOp.fn;
			for( int i=0; i<rlen; i++ )
				if( rnnz[i] < clen )
					ret.quickSetValue(i, 0, builtin.execute2(ret.quickGetValue(i, 0), 0));
		}
		
		//drop correction if necessary
		if(op.aggOp.correctionExists && inCP)
			ret.dropLastRowsOrColums(op.aggOp.correctionLocation);
	
		//post-processing
		ret.recomputeNonZeros();
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed uagg k="+op.getNumThreads()+" in "+time.stop());
		
		
		return ret;
	}
	
	@Override
	public MatrixBlock transposeSelfMatrixMultOperations(MatrixBlock out, MMTSJType tstype) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.transposeSelfMatrixMultOperations(out, tstype);
		}
				
		//single-threaded tsmm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().transposeSelfMatrixMultOperations(out, tstype);
		}

		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;

		//check for transpose type
		if( tstype != MMTSJType.LEFT ) //right not supported yet
			throw new DMLRuntimeException("Invalid MMTSJ type '"+tstype.toString()+"'.");
		
		//create output matrix block
		if( out == null )
			out = new MatrixBlock(clen, clen, false);
		else
			out.reset(clen, clen, false);
		out.allocateDenseBlock();
		
		if( !isEmptyBlock(false) ) {
			//compute matrix mult
			leftMultByTransposeSelf(_colGroups, out, 0, _colGroups.size());
			
			// post-processing
			out.recomputeNonZeros();
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed TSMM in "+time.stop());
		
		return out;
	}

	
	@Override
	public MatrixBlock transposeSelfMatrixMultOperations(MatrixBlock out, MMTSJType tstype, int k) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ){
			return super.transposeSelfMatrixMultOperations(out, tstype, k);
		}
		
		//multi-threaded tsmm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().transposeSelfMatrixMultOperations(out, tstype, k);
		}
		
		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//check for transpose type
		if( tstype != MMTSJType.LEFT ) //right not supported yet
			throw new DMLRuntimeException("Invalid MMTSJ type '"+tstype.toString()+"'.");
		
		//create output matrix block
		if( out == null )
			out = new MatrixBlock(clen, clen, false);
		else
			out.reset(clen, clen, false);
		out.allocateDenseBlock();
		
		if( !isEmptyBlock(false) ) {
			//compute matrix mult
			try {
				ExecutorService pool = Executors.newFixedThreadPool( k );
				ArrayList<MatrixMultTransposeTask> tasks = new ArrayList<MatrixMultTransposeTask>();
				int numgrp = _colGroups.size();
				int blklen = (int)(Math.ceil((double)numgrp/(2*k)));
				for( int i=0; i<2*k & i*blklen<clen; i++ )
					tasks.add(new MatrixMultTransposeTask(_colGroups, out, i*blklen, Math.min((i+1)*blklen, numgrp)));
				List<Future<Object>> ret = pool.invokeAll(tasks);
				for( Future<Object> tret : ret )
					tret.get(); //check for errors
				pool.shutdown();
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
			
			// post-processing
			out.recomputeNonZeros();
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed TSMM k="+k+" in "+time.stop());
		
		return out;
	}

	
	/**
	 * Multiply this matrix block by a column vector on the right.
	 * 
	 * @param vector
	 *            right-hand operand of the multiplication
	 * @param result
	 *            buffer to hold the result; must have the appropriate size
	 *            already
	 */
	private void rightMultByVector(MatrixBlock vector, MatrixBlock result)
		throws DMLRuntimeException 
	{
		// initialize and allocate the result
		result.allocateDenseBlock();

		// delegate matrix-vector operation to each column group
		for( ColGroup grp : _colGroups )
			if( grp instanceof ColGroupUncompressed ) //overwrites output
				grp.rightMultByVector(vector, result, 0, result.getNumRows());
		for( ColGroup grp : _colGroups )
			if( !(grp instanceof ColGroupUncompressed) ) //adds to output
				grp.rightMultByVector(vector, result, 0, result.getNumRows());
		
		// post-processing
		result.recomputeNonZeros();
	}

	/**
	 * Multi-threaded version of rightMultByVector.
	 * 
	 * @param vector matrix block vector
	 * @param result matrix block result
	 * @param k number of threads
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	private void rightMultByVector(MatrixBlock vector, MatrixBlock result, int k)
		throws DMLRuntimeException 
	{
		// initialize and allocate the result
		result.allocateDenseBlock();

		//multi-threaded execution of all groups
		try {
			ExecutorService pool = Executors.newFixedThreadPool( k );
			int rlen = getNumRows();
			int seqsz = BitmapEncoder.BITMAP_BLOCK_SZ;
			int blklen = (int)(Math.ceil((double)rlen/k));
			blklen += (blklen%seqsz != 0)?seqsz-blklen%seqsz:0;
			ArrayList<RightMatrixMultTask> tasks = new ArrayList<RightMatrixMultTask>();
			for( int i=0; i<k & i*blklen<getNumRows(); i++ )
				tasks.add(new RightMatrixMultTask(_colGroups, vector, result, i*blklen, Math.min((i+1)*blklen,rlen)));
			pool.invokeAll(tasks);	
			pool.shutdown();
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
		
		// post-processing
		result.recomputeNonZeros();
	}
	
	/**
	 * Multiply this matrix block by the transpose of a column vector (i.e.
	 * t(v)%*%X)
	 * 
	 * @param colGroups list of column groups
	 * @param vector
	 *            left-hand operand of the multiplication
	 * @param result
	 *            buffer to hold the result; must have the appropriate size
	 *            already
	 * @param doTranspose if true, transpose vector
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	private static void leftMultByVectorTranspose(List<ColGroup> colGroups, MatrixBlock vector, MatrixBlock result, boolean doTranspose) 
		throws DMLRuntimeException 
	{
		//transpose vector if required
		MatrixBlock rowVector = vector;
		if (doTranspose) {
			rowVector = new MatrixBlock(1, vector.getNumRows(), false);
			LibMatrixReorg.transpose(vector, rowVector);
		}
		
		// initialize and allocate the result
		result.reset();
		result.allocateDenseBlock();
		
		// delegate matrix-vector operation to each column group
		for (ColGroup grp : colGroups) {			
			grp.leftMultByRowVector(rowVector, result);
		}

		// post-processing
		result.recomputeNonZeros();
	}
	
	/**
	 * Multi-thread version of leftMultByVectorTranspose.
	 * 
	 * @param colGroups list of column groups
	 * @param vector
	 *            left-hand operand of the multiplication
	 * @param result
	 *            buffer to hold the result; must have the appropriate size
	 *            already
	 * @param doTranspose if true, transpose vector
	 * @param k number of threads
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	private static void leftMultByVectorTranspose(List<ColGroup> colGroups,MatrixBlock vector, MatrixBlock result, boolean doTranspose, int k) 
		throws DMLRuntimeException 
	{
		int kuc = Math.max(1, k - colGroups.size() + 1);
		
		//transpose vector if required
		MatrixBlock rowVector = vector;
		if (doTranspose) {
			rowVector = new MatrixBlock(1, vector.getNumRows(), false);
			LibMatrixReorg.transpose(vector, rowVector);
		}
		
		// initialize and allocate the result
		result.reset();
		result.allocateDenseBlock();

		//multi-threaded execution
		try {
			ExecutorService pool = Executors.newFixedThreadPool( Math.min(colGroups.size(), k) );
			ArrayList<LeftMatrixMultTask> tasks = new ArrayList<LeftMatrixMultTask>();
			for( ColGroup grp : colGroups )
				tasks.add(new LeftMatrixMultTask(grp, rowVector, result, kuc));
			pool.invokeAll(tasks);	
			pool.shutdown();
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}

		// post-processing
		result.recomputeNonZeros();
	}

	private static void leftMultByTransposeSelf(ArrayList<ColGroup> groups, MatrixBlock result, int gl, int gu)
		throws DMLRuntimeException 
	{
		final int numRows = groups.get(0).getNumRows();
		final int numGroups = groups.size();		
		
		//preallocated dense matrix block
		MatrixBlock lhs = new MatrixBlock(numRows, 1, false);
		lhs.allocateDenseBlock();
		
		//approach: for each colgroup, extract uncompressed columns one at-a-time
		//vector-matrix multiplies against remaining col groups
		for( int i=gl; i<gu; i++ ) 
		{
			//get current group and relevant col groups
			ColGroup group = groups.get(i);	
			int[] ixgroup = group.getColIndices();
			List<ColGroup> tmpList = groups.subList(i, numGroups);
			
			//for all uncompressed lhs columns vectors
			for( int j=0; j<ixgroup.length; j++ ) {
				//decompress single column
				lhs.reset(numRows, 1, false);				
				group.decompressToBlock(lhs, j);
				
				if( !lhs.isEmptyBlock(false) ) {
					//compute vector-matrix partial result
					MatrixBlock tmpret = new MatrixBlock(1,result.getNumColumns(),false);
					leftMultByVectorTranspose(tmpList, lhs, tmpret, true);								
					
					//write partial results (disjoint non-zeros)
					LinearAlgebraUtils.copyNonZerosToRowCol(result, tmpret, ixgroup[j]);	
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private ArrayList<ColGroup>[] createStaticTaskPartitioning(int k, boolean inclUncompressed)
	{
		// special case: single uncompressed col group
		if( _colGroups.size()==1 && _colGroups.get(0) instanceof ColGroupUncompressed ){
			return new ArrayList[0];
		}
		
		// initialize round robin col group distribution
		// (static task partitioning to reduce mem requirements/final agg)
		int numTasks = Math.min(k, _colGroups.size());
		ArrayList<ColGroup>[] grpParts = new ArrayList[numTasks];
		int pos = 0;
		for( ColGroup grp : _colGroups ){
			if( grpParts[pos]==null )
				grpParts[pos] = new ArrayList<ColGroup>();
			if( inclUncompressed || !(grp instanceof ColGroupUncompressed) ) {
				grpParts[pos].add(grp);
				pos = (pos==numTasks-1) ? 0 : pos+1;
			}
		}
		
		return grpParts;
	}

	private ColGroupUncompressed getUncompressedColGroup()
	{
		for( ColGroup grp : _colGroups )
			if( grp instanceof ColGroupUncompressed ) 
				return (ColGroupUncompressed)grp;
		
		return null;
	}

	private static class LeftMatrixMultTask implements Callable<Object> 
	{
		private ColGroup _group = null;
		private MatrixBlock _vect = null;
		private MatrixBlock _ret = null;
		private int _kuc = 1;
		
		protected LeftMatrixMultTask( ColGroup group, MatrixBlock vect, MatrixBlock ret, int kuc)  {
			_group = group;
			_vect = vect;
			_ret = ret;
			_kuc = kuc;
		}
		
		@Override
		public Object call() throws DMLRuntimeException 
		{
			// delegate matrix-vector operation to each column group
			if( _group instanceof ColGroupUncompressed && _kuc >1 && ColGroupBitmap.LOW_LEVEL_OPT )
				((ColGroupUncompressed)_group).leftMultByRowVector(_vect, _ret, _kuc);
			else
				_group.leftMultByRowVector(_vect, _ret);
			return null;
		}
	}

	private static class RightMatrixMultTask implements Callable<Object> 
	{
		private ArrayList<ColGroup> _groups = null;
		private MatrixBlock _vect = null;
		private MatrixBlock _ret = null;
		private int _rl = -1;
		private int _ru = -1;
		
		protected RightMatrixMultTask( ArrayList<ColGroup> groups, MatrixBlock vect, MatrixBlock ret, int rl, int ru)  {
			_groups = groups;
			_vect = vect;
			_ret = ret;
			_rl = rl;
			_ru = ru;
		}
		
		@Override
		public Object call() throws DMLRuntimeException 
		{
			// delegate vector-matrix operation to each column group
			for( ColGroup grp : _groups )
				if( grp instanceof ColGroupUncompressed ) //overwrites output
					grp.rightMultByVector(_vect, _ret, _rl, _ru);
			for( ColGroup grp : _groups )
				if( !(grp instanceof ColGroupUncompressed) ) //adds to output
					grp.rightMultByVector(_vect, _ret, _rl, _ru);
			return null;
		}
	}
	
	private static class MatrixMultTransposeTask implements Callable<Object> 
	{
		private ArrayList<ColGroup> _groups = null;
		private MatrixBlock _ret = null;
		private int _gl = -1;
		private int _gu = -1;
		
		protected MatrixMultTransposeTask(ArrayList<ColGroup> groups, MatrixBlock ret, int gl, int gu)  {
			_groups = groups;
			_ret = ret;
			_gl = gl;
			_gu = gu;
		}
		
		@Override
		public Object call() throws DMLRuntimeException {
			leftMultByTransposeSelf(_groups, _ret, _gl, _gu);
			return null;
		}
	}
	
	private static class UnaryAggregateTask implements Callable<MatrixBlock> 
	{
		private ArrayList<ColGroup> _groups = null;
		private int _rl = -1;
		private int _ru = -1;
		private MatrixBlock _ret = null;
		private AggregateUnaryOperator _op = null;
		
		protected UnaryAggregateTask( ArrayList<ColGroup> groups, MatrixBlock ret, int rl, int ru, AggregateUnaryOperator op)  {
			_groups = groups;
			_op = op;
			_rl = rl;
			_ru = ru;
			
			if( _op.indexFn instanceof ReduceAll ) { //sum
				_ret = new MatrixBlock(ret.getNumRows(), ret.getNumColumns(), false);
				_ret.allocateDenseBlock();
				if( _op.aggOp.increOp.fn instanceof Builtin )
					System.arraycopy(ret.getDenseBlock(), 0, _ret.getDenseBlock(), 0, ret.getNumRows()*ret.getNumColumns());
			}
			else { //colSums
				_ret = ret;
			}
		}
		
		@Override
		public MatrixBlock call() throws DMLRuntimeException {
			// delegate unary aggregate operation to each column group
			// (uncompressed column group handles separately)
			for( ColGroup grp : _groups )
				((ColGroupBitmap)grp).unaryAggregateOperations(_op, _ret, _rl, _ru);
			return _ret;
		}
	}

	private static class SizeEstimTask implements Callable<CompressedSizeInfo> 
	{
		private CompressedSizeEstimator _estim = null;
		private int _col = -1;
		
		protected SizeEstimTask( CompressedSizeEstimator estim, int col )  {
			_estim = estim;
			_col = col;
		}
		
		@Override
		public CompressedSizeInfo call() throws DMLRuntimeException {
			return _estim.estimateCompressedColGroupSize(new int[] { _col });
		}
	}

	private static class CompressTask implements Callable<ColGroup> 
	{
		private MatrixBlock _in = null;
		private CompressedSizeEstimator _estim = null;
		private HashMap<Integer, Double> _compRatios = null;
		private int _rlen = -1;
		private double _sp = -1;
		private int[] _colIndexes = null;
		
		protected CompressTask( MatrixBlock in, CompressedSizeEstimator estim, HashMap<Integer, Double> compRatios, int rlen, double sp, int[] colIndexes )  {
			_in = in;
			_estim = estim;
			_compRatios = compRatios;
			_rlen = rlen;
			_sp = sp;
			_colIndexes = colIndexes;
		}
		
		@Override
		public ColGroup call() throws DMLRuntimeException {
			return compressColGroup(_in, _estim, _compRatios, _rlen, _sp, _colIndexes);
		}
	}
	
	private static class DecompressTask implements Callable<Object> 
	{
		private List<ColGroup> _colGroups = null;
		private MatrixBlock _ret = null;
		private int _rl = -1;
		private int _ru = -1;
		
		protected DecompressTask( List<ColGroup> colGroups, MatrixBlock ret, int rl, int ru )  {
			_colGroups = colGroups;
			_ret = ret;
			_rl = rl;
			_ru = ru;
		}
		
		@Override
		public Object call() throws DMLRuntimeException {
			
			//preallocate sparse rows to avoid repeated alloc		
			if( _ret.isInSparseFormat() ) {
				int[] rnnz = new int[_ru-_rl];
				for (ColGroup grp : _colGroups)
					grp.countNonZerosPerRow(rnnz, _rl, _ru);
				SparseBlock rows = _ret.getSparseBlock();
				for( int i=_rl; i<_ru; i++ )
					rows.allocate(i, rnnz[i-_rl]);
			}
			
			//decompress row partition
			for (ColGroup grp : _colGroups)
				grp.decompressToBlock(_ret, _rl, _ru);

			//post processing (sort due to append)
			if( _ret.isInSparseFormat() )
				_ret.sortSparseRows(_rl, _ru);
			
			return null;
		}
	}
	
	//////////////////////////////////////////
	// Graceful fallback to uncompressed linear algebra
	
	@Override
	public MatrixValue unaryOperations(UnaryOperator op, MatrixValue result) 
			throws DMLRuntimeException {
		printDecompressWarning("unaryOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.unaryOperations(op, result);
	}

	@Override
	public void unaryOperationsInPlace(UnaryOperator op) 
			throws DMLRuntimeException {
		printDecompressWarning("unaryOperationsInPlace");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		tmp.unaryOperationsInPlace(op);
	}

	@Override
	public MatrixValue binaryOperations(BinaryOperator op, MatrixValue thatValue, MatrixValue result) 
			throws DMLRuntimeException {
		printDecompressWarning("binaryOperations", (MatrixBlock)thatValue);
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(thatValue);
		return left.binaryOperations(op, right, result);
	}

	@Override
	public void binaryOperationsInPlace(BinaryOperator op, MatrixValue thatValue) 
			throws DMLRuntimeException {
		printDecompressWarning("binaryOperationsInPlace", (MatrixBlock)thatValue);
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(thatValue);
		left.binaryOperationsInPlace(op, right);
	}

	@Override
	public void incrementalAggregate(AggregateOperator aggOp, MatrixValue correction, MatrixValue newWithCorrection)
			throws DMLRuntimeException {
		throw new DMLRuntimeException("CompressedMatrixBlock: incrementalAggregate not supported.");
	}

	@Override
	public void incrementalAggregate(AggregateOperator aggOp, MatrixValue newWithCorrection) 
			throws DMLRuntimeException {
		throw new DMLRuntimeException("CompressedMatrixBlock: incrementalAggregate not supported.");
	}

	@Override
	public MatrixValue reorgOperations(ReorgOperator op, MatrixValue ret, int startRow, int startColumn, int length)
			throws DMLRuntimeException {
		printDecompressWarning("reorgOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.reorgOperations(op, ret, startRow, startColumn, length);
	}

	@Override
	public MatrixBlock appendOperations(MatrixBlock that, MatrixBlock ret, boolean cbind) 
		throws DMLRuntimeException {
		if( cbind ) //use supported operation
			return appendOperations(that, ret);			
		printDecompressWarning("appendOperations-rbind", that);
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(that);
		return left.appendOperations(right, ret, cbind);
	}
	
	@Override
	public void appendOperations(MatrixValue v2,
			ArrayList<IndexedMatrixValue> outlist, int blockRowFactor,
			int blockColFactor, boolean cbind, boolean m2IsLast, int nextNCol)
			throws DMLRuntimeException {
		printDecompressWarning("appendOperations", (MatrixBlock)v2);
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(v2);
		left.appendOperations(right, outlist, blockRowFactor, blockColFactor, cbind, m2IsLast, nextNCol);
	}

	@Override
	public void permutationMatrixMultOperations(MatrixValue m2Val, MatrixValue out1Val, MatrixValue out2Val)
			throws DMLRuntimeException {
		permutationMatrixMultOperations(m2Val, out1Val, out2Val, 1);
	}

	@Override
	public void permutationMatrixMultOperations(MatrixValue m2Val, MatrixValue out1Val, MatrixValue out2Val, int k)
			throws DMLRuntimeException {
		printDecompressWarning("permutationMatrixMultOperations", (MatrixBlock)m2Val);
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(m2Val);
		left.permutationMatrixMultOperations(right, out1Val, out2Val, k);
	}

	@Override
	public MatrixBlock leftIndexingOperations(MatrixBlock rhsMatrix, int rl, int ru, int cl, int cu, MatrixBlock ret, UpdateType update)
			throws DMLRuntimeException {
		printDecompressWarning("leftIndexingOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(rhsMatrix);
		return left.leftIndexingOperations(right, rl, ru, cl, cu, ret, update);
	}

	@Override
	public MatrixBlock leftIndexingOperations(ScalarObject scalar, int rl, int cl, MatrixBlock ret, UpdateType update)
			throws DMLRuntimeException {
		printDecompressWarning("leftIndexingOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.leftIndexingOperations(scalar, rl, cl, ret, update);
	}

	@Override
	public MatrixBlock sliceOperations(int rl, int ru, int cl, int cu, CacheBlock ret) 
			throws DMLRuntimeException {
		printDecompressWarning("sliceOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.sliceOperations(rl, ru, cl, cu, ret);
	}

	@Override
	public void sliceOperations(ArrayList<IndexedMatrixValue> outlist, IndexRange range, 
			int rowCut, int colCut, int normalBlockRowFactor,
			int normalBlockColFactor, int boundaryRlen, int boundaryClen) {
		printDecompressWarning("sliceOperations");
		try {
			MatrixBlock tmp = isCompressed() ? decompress() : this;
			tmp.sliceOperations(outlist, range, rowCut, colCut, normalBlockRowFactor,
					normalBlockColFactor, boundaryRlen, boundaryClen);
		}
		catch(DMLRuntimeException ex) {
			throw new RuntimeException(ex);
		}
	}

	@Override
	public MatrixValue zeroOutOperations(MatrixValue result, IndexRange range, boolean complementary) 
			throws DMLRuntimeException {
		printDecompressWarning("zeroOutOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.zeroOutOperations(result, range, complementary);
	}
	
	@Override
	public MatrixValue aggregateUnaryOperations(AggregateUnaryOperator op,
			MatrixValue result, int blockingFactorRow, int blockingFactorCol,
			MatrixIndexes indexesIn) throws DMLRuntimeException {
		printDecompressWarning("aggregateUnaryOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.aggregateUnaryOperations(op, result, blockingFactorRow, blockingFactorCol, indexesIn);
	}

	@Override
	public CM_COV_Object cmOperations(CMOperator op) throws DMLRuntimeException {
		printDecompressWarning("cmOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.cmOperations(op);
	}

	@Override
	public CM_COV_Object cmOperations(CMOperator op, MatrixBlock weights)
			throws DMLRuntimeException {
		printDecompressWarning("cmOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(weights);
		return left.cmOperations(op, right);
	}

	@Override
	public CM_COV_Object covOperations(COVOperator op, MatrixBlock that)
			throws DMLRuntimeException {
		printDecompressWarning("covOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(that);
		return left.covOperations(op, right);
	}

	@Override
	public CM_COV_Object covOperations(COVOperator op, MatrixBlock that, MatrixBlock weights) 
			throws DMLRuntimeException {
		printDecompressWarning("covOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right1 = getUncompressed(that);
		MatrixBlock right2 = getUncompressed(weights);
		return left.covOperations(op, right1, right2);
	}

	@Override
	public MatrixValue sortOperations(MatrixValue weights, MatrixValue result)
			throws DMLRuntimeException {
		printDecompressWarning("sortOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(weights);
		return left.sortOperations(right, result);
	}

	@Override
	public MatrixValue aggregateBinaryOperations(MatrixIndexes m1Index,
			MatrixValue m1Value, MatrixIndexes m2Index, MatrixValue m2Value,
			MatrixValue result, AggregateBinaryOperator op)
			throws DMLRuntimeException {
		printDecompressWarning("aggregateBinaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(m2Value);
		return left.aggregateBinaryOperations(m1Index, left, m2Index, right, result, op);
	}

	@Override
	public ScalarObject aggregateTernaryOperations(MatrixBlock m1, MatrixBlock m2, MatrixBlock m3, AggregateBinaryOperator op)
			throws DMLRuntimeException {
		printDecompressWarning("aggregateTernaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right1 = getUncompressed(m2);
		MatrixBlock right2 = getUncompressed(m3);
		return left.aggregateTernaryOperations(left, right1, right2, op);
	}

	@Override
	public MatrixBlock uaggouterchainOperations(MatrixBlock mbLeft, MatrixBlock mbRight, 
			MatrixBlock mbOut, BinaryOperator bOp, AggregateUnaryOperator uaggOp) 
			throws DMLRuntimeException {
		printDecompressWarning("uaggouterchainOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(mbRight);
		return left.uaggouterchainOperations(left, right, mbOut, bOp, uaggOp);
	}

	@Override
	public MatrixBlock groupedAggOperations(MatrixValue tgt, MatrixValue wghts, MatrixValue ret, int ngroups, Operator op)
			throws DMLRuntimeException {
		return groupedAggOperations(tgt, wghts, ret, ngroups, op, 1);
	}

	@Override
	public MatrixBlock groupedAggOperations(MatrixValue tgt, MatrixValue wghts,
			MatrixValue ret, int ngroups, Operator op, int k)
			throws DMLRuntimeException {
		printDecompressWarning("groupedAggOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(wghts);
		return left.groupedAggOperations(left, right, ret, ngroups, op, k);
	}

	@Override
	public MatrixBlock removeEmptyOperations(MatrixBlock ret, boolean rows, MatrixBlock select) 
			throws DMLRuntimeException {
		printDecompressWarning("removeEmptyOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.removeEmptyOperations(ret, rows, select);
	}

	@Override
	public MatrixBlock removeEmptyOperations(MatrixBlock ret, boolean rows)
			throws DMLRuntimeException {
		printDecompressWarning("removeEmptyOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.removeEmptyOperations(ret, rows);
	}

	@Override
	public MatrixBlock rexpandOperations(MatrixBlock ret, double max,
			boolean rows, boolean cast, boolean ignore)
			throws DMLRuntimeException {
		printDecompressWarning("rexpandOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.rexpandOperations(ret, max, rows, cast, ignore);
	}

	@Override
	public MatrixValue replaceOperations(MatrixValue result, double pattern, double replacement) 
			throws DMLRuntimeException {
		printDecompressWarning("replaceOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		return tmp.replaceOperations(result, pattern, replacement);
	}

	@Override
	public void ternaryOperations(Operator op, double scalar,
			MatrixValue that, CTableMap resultMap, MatrixBlock resultBlock)
			throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(that);
		left.ternaryOperations(op, scalar, right, resultMap, resultBlock);
	}

	@Override
	public void ternaryOperations(Operator op, double scalar,
			double scalar2, CTableMap resultMap, MatrixBlock resultBlock)
			throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		tmp.ternaryOperations(op, scalar, scalar2, resultMap, resultBlock);
	}

	@Override
	public void ternaryOperations(Operator op, MatrixIndexes ix1,
			double scalar, boolean left, int brlen, CTableMap resultMap,
			MatrixBlock resultBlock) throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock tmp = isCompressed() ? decompress() : this;
		tmp.ternaryOperations(op, ix1, scalar, left, brlen, resultMap, resultBlock);
	}

	@Override
	public void ternaryOperations(Operator op, MatrixValue that,
			double scalar, boolean ignoreZeros, CTableMap resultMap,
			MatrixBlock resultBlock) throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(that);
		left.ternaryOperations(op, right, scalar, ignoreZeros, resultMap, resultBlock);
	}

	@Override
	public void ternaryOperations(Operator op, MatrixValue that, double scalar, MatrixBlock resultBlock)
			throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right = getUncompressed(that);
		left.ternaryOperations(op, right, scalar, resultBlock);
	}

	@Override
	public void ternaryOperations(Operator op, MatrixValue that,
			MatrixValue that2, CTableMap resultMap)
			throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right1 = getUncompressed(that);
		MatrixBlock right2 = getUncompressed(that2);
		left.ternaryOperations(op, right1, right2, resultMap);
	}

	@Override
	public void ternaryOperations(Operator op, MatrixValue that,
			MatrixValue that2, CTableMap resultMap, MatrixBlock resultBlock)
			throws DMLRuntimeException {
		printDecompressWarning("ternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right1 = getUncompressed(that);
		MatrixBlock right2 = getUncompressed(that2);
		left.ternaryOperations(op, right1, right2, resultMap, resultBlock);
	}

	@Override
	public MatrixValue quaternaryOperations(QuaternaryOperator qop,
			MatrixValue um, MatrixValue vm, MatrixValue wm, MatrixValue out)
			throws DMLRuntimeException {
		return quaternaryOperations(qop, um, vm, wm, out, 1);
	}

	@Override
	public MatrixValue quaternaryOperations(QuaternaryOperator qop, MatrixValue um, 
			MatrixValue vm, MatrixValue wm, MatrixValue out, int k) 
			throws DMLRuntimeException {
		printDecompressWarning("quaternaryOperations");
		MatrixBlock left = isCompressed() ? decompress() : this;
		MatrixBlock right1 = getUncompressed(um);
		MatrixBlock right2 = getUncompressed(vm);
		MatrixBlock right3 = getUncompressed(wm);
		return left.quaternaryOperations(qop, right1, right2, right3, out, k);
	}

	@Override
	public MatrixBlock randOperationsInPlace(RandomMatrixGenerator rgen,
			long[] nnzInBlock, Well1024a bigrand, long bSeed)
			throws DMLRuntimeException {
		throw new RuntimeException("CompressedMatrixBlock: randOperationsInPlace not supported.");
	}

	@Override
	public MatrixBlock randOperationsInPlace(RandomMatrixGenerator rgen,
			long[] nnzInBlock, Well1024a bigrand, long bSeed, int k)
			throws DMLRuntimeException {
		throw new RuntimeException("CompressedMatrixBlock: randOperationsInPlace not supported.");
	}

	@Override
	public MatrixBlock seqOperationsInPlace(double from, double to, double incr)
			throws DMLRuntimeException {
		//output should always be uncompressed
		throw new RuntimeException("CompressedMatrixBlock: seqOperationsInPlace not supported.");
	}

	private static boolean isCompressed(MatrixBlock mb) {
		return (mb instanceof CompressedMatrixBlock && ((CompressedMatrixBlock)mb).isCompressed());
	}

	private static MatrixBlock getUncompressed(MatrixValue mVal) 
			throws DMLRuntimeException {
		return isCompressed((MatrixBlock)mVal) ? 
				((CompressedMatrixBlock)mVal).decompress() : 
				(MatrixBlock)mVal;
	}

	private void printDecompressWarning(String operation) {
		if( isCompressed() ) {
			LOG.warn("Operation '"+operation+"' not supported yet - decompressing for ULA operations.");
		}
	}

	private void printDecompressWarning(String operation, MatrixBlock m2) {
		if( isCompressed() || isCompressed(m2) ) {
			LOG.warn("Operation '"+operation+"' not supported yet - decompressing for ULA operations.");
		}
	}
}
