package com.linkedin.pinot.core.indexsegment;

import java.util.Iterator;

import com.linkedin.pinot.common.data.RowEvent;
import com.linkedin.pinot.common.query.request.FilterQuery;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.core.common.Predicate;
import com.linkedin.pinot.core.operator.DataSource;

/**
 * This is the interface of index segment. The index type of index segment
 * should be one of the supported {@link com.linkedin.pinot.core.indexsegment.IndexType
 * IndexType}.
 * 
 * @author Xiang Fu <xiafu@linkedin.com>
 * 
 */
public interface IndexSegment {
	/**
	 * @return
	 */
	public IndexType getIndexType();

	/**
	 * @return
	 */
	public String getSegmentName();

	/**
	 * @return
	 */
	public String getAssociatedDirectory();


	/**
	 * @return SegmentMetadata
	 */
	public SegmentMetadata getSegmentMetadata();

	/**
	 * @param query
	 * @return Iterator<RowEvent>
	 */
	public Iterator<RowEvent> processFilterQuery(FilterQuery query);

	/**
	 * @param query
	 * @return Iterator<Integer>
	 */
	public Iterator<Integer> getDocIdIterator(FilterQuery query);

	/**
	 * @param column
	 * @return ColumnarReader
	 */
	public ColumnarReader getColumnarReader(String column);

	/**
	 * 
	 * @param columnName
	 * @return
	 */
	DataSource getDataSource(String columnName);

	/**
	 * 
	 * @param columnName
	 * @param p
	 * @return
	 */
	DataSource getDataSource(String columnName, Predicate p);
}