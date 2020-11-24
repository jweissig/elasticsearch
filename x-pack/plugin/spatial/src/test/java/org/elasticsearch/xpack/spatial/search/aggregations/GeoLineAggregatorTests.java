/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.spatial.search.aggregations;

import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.geo.GeoEncodingUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.geo.GeometryTestUtils;
import org.elasticsearch.geometry.Point;
import org.elasticsearch.index.mapper.GeoPointFieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.aggregations.AggregatorTestCase;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.support.MultiValuesSourceFieldConfig;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xpack.spatial.SpatialPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.equalTo;

public class GeoLineAggregatorTests extends AggregatorTestCase {

    @Override
    protected List<SearchPlugin> getSearchPlugins() {
        return Collections.singletonList(new SpatialPlugin());
    }

    // test that missing values are ignored
    public void testMissingValues() throws IOException {
        MultiValuesSourceFieldConfig valueConfig = new MultiValuesSourceFieldConfig.Builder()
            .setFieldName("value_field")
            .build();
        MultiValuesSourceFieldConfig sortConfig = new MultiValuesSourceFieldConfig.Builder().setFieldName("sort_field").build();
        GeoLineAggregationBuilder lineAggregationBuilder = new GeoLineAggregationBuilder("_name")
            .point(valueConfig)
            .sortOrder(SortOrder.ASC)
            .sort(sortConfig)
            .size(10);

        TermsAggregationBuilder aggregationBuilder = new TermsAggregationBuilder("_name")
            .field("group_id")
            .subAggregation(lineAggregationBuilder);

        long lonLat = (((long) GeoEncodingUtils.encodeLongitude(90.0)) << 32) | GeoEncodingUtils.encodeLatitude(45.0) & 0xffffffffL;
        //input
        long[] points = new long[] {lonLat, 0, lonLat, 0,lonLat, lonLat, lonLat};
        double[] sortValues = new double[]{1, 0, 2, 0, 3, 4, 5};
        //expected
        long[] expectedAggPoints = new long[] {lonLat, lonLat, lonLat, lonLat, lonLat};
        double[] expectedAggSortValues = new double[]{
            NumericUtils.doubleToSortableLong(1),
            NumericUtils.doubleToSortableLong(2),
            NumericUtils.doubleToSortableLong(3),
            NumericUtils.doubleToSortableLong(4),
            NumericUtils.doubleToSortableLong(5)
        };

        testCase(new MatchAllDocsQuery(), aggregationBuilder, iw -> {

            for (int i = 0; i < points.length; i++) {
                if (points[i] == 0) {
                    // do not index value
                    iw.addDocument(Collections.singletonList(new SortedDocValuesField("group_id", new BytesRef("group"))));
                } else {
                    iw.addDocument(Arrays.asList(new LatLonDocValuesField("value_field", 45.0, 90.0),
                        new SortedNumericDocValuesField("sort_field", NumericUtils.doubleToSortableLong(sortValues[i])),
                        new SortedDocValuesField("group_id", new BytesRef("group"))));
                }
            }
        }, terms -> {
            assertThat(terms.getBuckets().size(), equalTo(1));
            InternalGeoLine geoLine = terms.getBuckets().get(0).getAggregations().get("_name");
            assertThat(geoLine.length(), equalTo(5));
            assertTrue(geoLine.isComplete());
            assertArrayEquals(expectedAggPoints, geoLine.line());
            assertArrayEquals(expectedAggSortValues, geoLine.sortVals(), 0d);
        });
    }

    public void testAscending() throws IOException {
        testAggregator(SortOrder.ASC);
    }

    public void testDescending() throws IOException {
        testAggregator(SortOrder.DESC);
    }

    private void testAggregator(SortOrder sortOrder) throws IOException {
        int size = randomIntBetween(1, GeoLineAggregationBuilder.MAX_PATH_SIZE);
        MultiValuesSourceFieldConfig valueConfig = new MultiValuesSourceFieldConfig.Builder()
            .setFieldName("value_field")
            .build();
        MultiValuesSourceFieldConfig sortConfig = new MultiValuesSourceFieldConfig.Builder().setFieldName("sort_field").build();
        GeoLineAggregationBuilder lineAggregationBuilder = new GeoLineAggregationBuilder("_name")
            .point(valueConfig)
            .sortOrder(sortOrder)
            .sort(sortConfig)
            .size(size);
        TermsAggregationBuilder aggregationBuilder = new TermsAggregationBuilder("_name")
            .field("group_id")
            .subAggregation(lineAggregationBuilder);

        int numGroups = randomIntBetween(1, 2);
        Map<String, InternalGeoLine> lines = new HashMap<>(numGroups);
        Map<Integer, long[]> indexedPoints = new HashMap<>(numGroups);
        Map<Integer, double[]> indexedSortValues = new HashMap<>(numGroups);
        for (int groupOrd = 0; groupOrd < numGroups; groupOrd++) {
            int numPoints = randomIntBetween(2, 2 * size);
            boolean complete = numPoints <= size;
            long[] points = new long[numPoints];
            double[] sortValues = new double[numPoints];
            for (int i = 0; i < numPoints; i++) {
                Point point = GeometryTestUtils.randomPoint(false);
                int encodedLat = GeoEncodingUtils.encodeLatitude(point.getLat());
                int encodedLon = GeoEncodingUtils.encodeLongitude(point.getLon());
                long lonLat = (((long) encodedLon) << 32) | encodedLat & 0xffffffffL;
                points[i] = lonLat;
                sortValues[i] = SortOrder.ASC.equals(sortOrder) ? i : numPoints - i;
            }
            int lineSize = Math.min(numPoints, size);
            // re-sort line to be ascending
            long[] linePoints = Arrays.copyOf(points, lineSize);
            double[] lineSorts = Arrays.copyOf(sortValues, lineSize);
            new PathArraySorter(linePoints, lineSorts, SortOrder.ASC).sort();

            lines.put(String.valueOf(groupOrd), new InternalGeoLine("_name",
                linePoints, lineSorts, null, complete, true, sortOrder, size));

            for (int i = 0; i < randomIntBetween(1, numPoints); i++) {
                int idx1 = randomIntBetween(0, numPoints - 1);
                int idx2 = randomIntBetween(0, numPoints - 1);
                final long tmpPoint = points[idx1];
                points[idx1] = points[idx2];
                points[idx2] = tmpPoint;
                final double tmpSortValue = sortValues[idx1];
                sortValues[idx1] = sortValues[idx2];
                sortValues[idx2] = tmpSortValue;
            }
            indexedPoints.put(groupOrd, points);
            indexedSortValues.put(groupOrd, sortValues);
        }


        testCase(new MatchAllDocsQuery(), aggregationBuilder, iw -> {
            for (int group = 0; group < numGroups; group++) {
                long[] points = indexedPoints.get(group);
                double[] sortValues = indexedSortValues.get(group);
                for (int i = 0; i < points.length; i++) {
                    int x = (int) (points[i] >> 32);
                    int y = (int) points[i];
                    iw.addDocument(Arrays.asList(new LatLonDocValuesField("value_field",
                            GeoEncodingUtils.decodeLatitude(y),
                            GeoEncodingUtils.decodeLongitude(x)),
                        new SortedNumericDocValuesField("sort_field", NumericUtils.doubleToSortableLong(sortValues[i])),
                        new SortedDocValuesField("group_id", new BytesRef(String.valueOf(group)))));
                }
            }
        }, terms -> {
            for (Terms.Bucket bucket : terms.getBuckets()) {
                InternalGeoLine expectedGeoLine = lines.get(bucket.getKeyAsString());
                InternalGeoLine geoLine = bucket.getAggregations().get("_name");
                assertThat(geoLine.length(), equalTo(expectedGeoLine.length()));
                assertThat(geoLine.isComplete(), equalTo(expectedGeoLine.isComplete()));
                for (int i = 0; i < geoLine.sortVals().length; i++) {
                    geoLine.sortVals()[i] = NumericUtils.sortableLongToDouble((long) geoLine.sortVals()[i]);
                }
                assertArrayEquals(expectedGeoLine.sortVals(), geoLine.sortVals(), 0d);
                assertArrayEquals(expectedGeoLine.line(), geoLine.line());
            }
        });
    }

    private void testCase(Query query, TermsAggregationBuilder aggregationBuilder,
                          CheckedConsumer<RandomIndexWriter, IOException> buildIndex,
                          Consumer<Terms> verify) throws IOException {
        testCase(query, aggregationBuilder, buildIndex, verify, NumberFieldMapper.NumberType.LONG);
    }

    private void testCase(Query query, TermsAggregationBuilder aggregationBuilder,
                          CheckedConsumer<RandomIndexWriter, IOException> buildIndex,
                          Consumer<Terms> verify,
                          NumberFieldMapper.NumberType fieldNumberType) throws IOException {

        Directory directory = newDirectory();
        RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory);
        buildIndex.accept(indexWriter);
        indexWriter.close();
        IndexReader indexReader = DirectoryReader.open(directory);
        IndexSearcher indexSearcher = newSearcher(indexReader, true, true);

        try {
            MappedFieldType fieldType = new GeoPointFieldMapper.GeoPointFieldType("value_field");
            MappedFieldType groupFieldType = new KeywordFieldMapper.KeywordFieldType("group_id");
            MappedFieldType fieldType2 = new NumberFieldMapper.NumberFieldType("sort_field", fieldNumberType);

            Terms terms = searchAndReduce(indexSearcher, new MatchAllDocsQuery(), aggregationBuilder,
                fieldType, fieldType2, groupFieldType);
            verify.accept(terms);
        } finally {
            indexReader.close();
            directory.close();
        }
    }
}
