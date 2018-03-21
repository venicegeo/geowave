package mil.nga.giat.geowave.service.grpc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ContentFeatureCollection;
import org.geotools.factory.FactoryRegistryException;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.ParameterException;
import com.google.protobuf.util.Timestamps;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTReader;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import mil.nga.giat.geowave.adapter.vector.GeotoolsFeatureDataAdapter;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWaveGTDataStore;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWavePluginConfig;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWavePluginException;
import mil.nga.giat.geowave.adapter.vector.query.cql.CQLQuery;
import mil.nga.giat.geowave.core.cli.operations.config.options.ConfigOptions;
import mil.nga.giat.geowave.core.geotime.store.filter.SpatialQueryFilter.CompareOperation;
import mil.nga.giat.geowave.core.geotime.store.query.SpatialQuery;
import mil.nga.giat.geowave.core.geotime.store.query.SpatialTemporalQuery;
import mil.nga.giat.geowave.core.geotime.store.query.TemporalRange;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.DataStore;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.index.IndexStore;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.operations.remote.options.StoreLoader;
import mil.nga.giat.geowave.core.store.query.QueryOptions;
import mil.nga.giat.geowave.service.grpc.protobuf.CQLQueryParameters;
import mil.nga.giat.geowave.service.grpc.protobuf.Feature;
import mil.nga.giat.geowave.service.grpc.protobuf.SpatialQueryParameters;
import mil.nga.giat.geowave.service.grpc.protobuf.SpatialTemporalQueryParameters;
import mil.nga.giat.geowave.service.grpc.protobuf.TemporalConstraints;
import mil.nga.giat.geowave.service.grpc.protobuf.VectorQueryGrpc;
import mil.nga.giat.geowave.service.grpc.protobuf.VectorQueryParameters;

public class GeoWaveGrpcServer
{
	private static final Logger LOGGER = LoggerFactory.getLogger(
			GeoWaveGrpcServer.class.getName());

	private final int port;
	private final Server server;

	public GeoWaveGrpcServer(
			final int port )
			throws IOException {
		this.port = port;
		server = ServerBuilder
				.forPort(
						port)
				.addService(
						new GeoWaveGrpcVectorQueryService())
				.build();
	}

	/** Start serving requests. */
	public void start()
			throws IOException {
		server.start();
		LOGGER.info(
				"Server started, listening on " + port);
		Runtime.getRuntime().addShutdownHook(
				new Thread() {
					@Override
					public void run() {
						// Use stderr here since the logger may have been reset
						// by its JVM shutdown hook.
						System.err.println(
								"*** shutting down gRPC server since JVM is shutting down");
						GeoWaveGrpcServer.this.stop();
						System.err.println(
								"*** server shut down");
					}
				});
	}

	/** Stop serving requests and shutdown resources. */
	public void stop() {
		if (server != null) {
			server.shutdown();
		}
	}

	/**
	 * Await termination on the main thread since the grpc library uses daemon
	 * threads.
	 */
	public void blockUntilShutdown()
			throws InterruptedException {
		if (server != null) {
			server.awaitTermination();
		}
	}

	private static class GeoWaveGrpcVectorQueryService extends
			VectorQueryGrpc.VectorQueryImplBase
	{
		@Override
		public void vectorQuery(
				final VectorQueryParameters request,
				final StreamObserver<Feature> responseObserver ) {
			final String storeName = request.getStoreName();
			final StoreLoader storeLoader = new StoreLoader(
					storeName);
			// first check to make sure the data store exists
			if (!storeLoader.loadFromConfig(
					ConfigOptions.getDefaultPropertyFile())) {
				throw new ParameterException(
						"Cannot find store name: " + storeLoader.getStoreName());
			}

			GeoWaveGTDataStore gtStore = null;
			try {
				gtStore = new GeoWaveGTDataStore(
						new GeoWavePluginConfig(
								storeLoader.getDataStorePlugin()));
			}
			catch (final IOException | GeoWavePluginException e) {
				LOGGER.error(
						"Exception encountered instantiating GeoWaveGTDataStore",
						e);
			}

			Filter filter = null;
			try {
				filter = CQL.toFilter(
						request.getQuery());
			}
			catch (final CQLException e) {
				LOGGER.error(
						"Exception encountered creating filter from CQL",
						e);
			}

			ContentFeatureCollection featureCollection = null;
			try {
				featureCollection = gtStore
						.getFeatureSource(
								request.getAdapterId().toString())
						.getFeatures(
								filter);
			}
			catch (final IOException | NullPointerException e) {
				LOGGER.error(
						"Exception encountered getting feature collection",
						e);
			}

			try (final SimpleFeatureIterator iterator = featureCollection.features()) {

				while (iterator.hasNext()) {
					final SimpleFeature simpleFeature = iterator.next();
					final SimpleFeatureType type = simpleFeature.getType();
					final Feature.Builder b = Feature.newBuilder();
					for (int i = 0; i < type.getAttributeDescriptors().size(); i++) {
						b.putAttributes(
								type
										.getAttributeDescriptors()
										.get(
												i)
										.getLocalName(),
								simpleFeature.getAttribute(
										i) == null ? ""
												: simpleFeature
														.getAttribute(
																i)
														.toString());
					}
					final Feature f = b.build();
					responseObserver.onNext(
							f);
				}
			}
			catch (final NullPointerException e) {
				LOGGER.error(
						"Exception encountered",
						e);
			}
		}

		@Override
		public void cqlQuery(
				final CQLQueryParameters request,
				final StreamObserver<Feature> responseObserver ) {

			final String cql = request.getCql();
			final String storeName = request.getBaseParams().getStoreName();
			final StoreLoader storeLoader = new StoreLoader(
					storeName);

			ByteArrayId adapterId = new ByteArrayId(
					request.getBaseParams().getAdapterId().toByteArray());
			ByteArrayId indexId = new ByteArrayId(
					request.getBaseParams().getIndexId().toByteArray());

			if (adapterId.getString().equalsIgnoreCase(
					"")) {
				adapterId = null;
			}
			if (indexId.getString().equalsIgnoreCase(
					"")) {
				indexId = null;
			}

			// first check to make sure the data store exists
			if (!storeLoader.loadFromConfig(
					ConfigOptions.getDefaultPropertyFile())) {
				throw new ParameterException(
						"Cannot find store name: " + storeLoader.getStoreName());
			}

			// get a handle to the relevant stores
			final DataStore dataStore = storeLoader.createDataStore();
			final AdapterStore adapterStore = storeLoader.createAdapterStore();
			final IndexStore indexStore = storeLoader.createIndexStore();

			GeotoolsFeatureDataAdapter adapter = null;
			PrimaryIndex pIndex = null;

			if (adapterId != null) {
				adapter = (GeotoolsFeatureDataAdapter) adapterStore.getAdapter(
						adapterId);
			}

			if (indexId != null) {
				pIndex = (PrimaryIndex) indexStore.getIndex(
						indexId);
			}

			try (final CloseableIterator<SimpleFeature> iterator = dataStore.query(
					new QueryOptions(
							adapterId,
							indexId),
					CQLQuery.createOptimalQuery(
							cql,
							adapter,
							pIndex))) {

				while (iterator.hasNext()) {
					final SimpleFeature simpleFeature = iterator.next();
					final SimpleFeatureType type = simpleFeature.getType();
					final Feature.Builder b = Feature.newBuilder();
					for (int i = 0; i < type.getAttributeDescriptors().size(); i++) {
						b.putAttributes(
								type
										.getAttributeDescriptors()
										.get(
												i)
										.getLocalName(),
								simpleFeature.getAttribute(
										i) == null ? ""
												: simpleFeature
														.getAttribute(
																i)
														.toString());
					}
					final Feature f = b.build();
					responseObserver.onNext(
							f);
				}
				responseObserver.onCompleted();
			}
			catch (final CQLException e) {
				LOGGER.error(
						"Exception encountered CQL.createOptimalQuery",
						e);
			}
			catch (final IOException e) {
				LOGGER.error(
						"Exception encountered closing iterator",
						e);
			}

		}

		@Override
		public void spatialQuery(
				final SpatialQueryParameters request,
				final StreamObserver<Feature> responseObserver ) {

			final String storeName = request.getBaseParams().getStoreName();
			final StoreLoader storeLoader = new StoreLoader(
					storeName);

			ByteArrayId adapterId = new ByteArrayId(
					request.getBaseParams().getAdapterId().toByteArray());
			ByteArrayId indexId = new ByteArrayId(
					request.getBaseParams().getIndexId().toByteArray());

			if (adapterId.getString().equalsIgnoreCase(
					"")) {
				adapterId = null;
			}
			if (indexId.getString().equalsIgnoreCase(
					"")) {
				indexId = null;
			}

			// first check to make sure the data store exists
			if (!storeLoader.loadFromConfig(
					ConfigOptions.getDefaultPropertyFile())) {
				throw new ParameterException(
						"Cannot find store name: " + storeLoader.getStoreName());
			}

			final DataStore dataStore = storeLoader.createDataStore();

			final String geomDefinition = request.getGeometry();
			Geometry queryGeom = null;

			try {
				queryGeom = new WKTReader(
						JTSFactoryFinder.getGeometryFactory()).read(
								geomDefinition);
			}
			catch (final FactoryRegistryException | com.vividsolutions.jts.io.ParseException e) {
				LOGGER.error(
						"Exception encountered creating query geometry",
						e);
			}

			final QueryOptions options = new QueryOptions(
					adapterId,
					indexId);

			try (final CloseableIterator<SimpleFeature> iterator = dataStore.query(
					options,
					new SpatialQuery(
							queryGeom))) {
				while (iterator.hasNext()) {
					final SimpleFeature simpleFeature = iterator.next();
					final SimpleFeatureType type = simpleFeature.getType();
					final Feature.Builder b = Feature.newBuilder();
					for (int i = 0; i < type.getAttributeDescriptors().size(); i++) {
						b.putAttributes(
								type
										.getAttributeDescriptors()
										.get(
												i)
										.getLocalName(),
								simpleFeature.getAttribute(
										i) == null ? ""
												: simpleFeature
														.getAttribute(
																i)
														.toString());
					}
					final Feature f = b.build();
					responseObserver.onNext(
							f);
				}
				responseObserver.onCompleted();
			}
			catch (final IOException e) {
				LOGGER.error(
						"Exception encountered closing iterator",
						e);
			}
		}

		@Override
		public void spatialTemporalQuery(
				final SpatialTemporalQueryParameters request,
				final StreamObserver<Feature> responseObserver ) {

			final String storeName = request.getSpatialParams().getBaseParams().getStoreName();
			final StoreLoader storeLoader = new StoreLoader(
					storeName);

			// first check to make sure the data store exists
			if (!storeLoader.loadFromConfig(
					ConfigOptions.getDefaultPropertyFile())) {
				throw new ParameterException(
						"Cannot find store name: " + storeLoader.getStoreName());
			}

			final DataStore dataStore = storeLoader.createDataStore();

			ByteArrayId adapterId = new ByteArrayId(
					request.getSpatialParams().getBaseParams().getAdapterId().toByteArray());
			ByteArrayId indexId = new ByteArrayId(
					request.getSpatialParams().getBaseParams().getIndexId().toByteArray());

			if (adapterId.getString().equalsIgnoreCase(
					"")) {
				adapterId = null;
			}
			if (indexId.getString().equalsIgnoreCase(
					"")) {
				indexId = null;
			}

			final int constraintCount = request.getTemporalConstraintsCount();
			final ArrayList<TemporalRange> temporalRanges = new ArrayList<>();
			for (int i = 0; i < constraintCount; i++) {
				final TemporalConstraints t = request.getTemporalConstraints(
						i);
				temporalRanges.add(
						new TemporalRange(
								new Date(
										Timestamps.toMillis(
												t.getStartTime())),
								new Date(
										Timestamps.toMillis(
												t.getEndTime()))));
			}

			final String geomDefinition = request.getSpatialParams().getGeometry();
			Geometry queryGeom = null;
			mil.nga.giat.geowave.core.geotime.store.query.TemporalConstraints temporalConstraints = null;

			try {
				queryGeom = new WKTReader(
						JTSFactoryFinder.getGeometryFactory()).read(
								geomDefinition);
			}
			catch (final FactoryRegistryException | com.vividsolutions.jts.io.ParseException e) {
				LOGGER.error(
						"Exception encountered creating query geometry",
						e);
			}

			temporalConstraints = new mil.nga.giat.geowave.core.geotime.store.query.TemporalConstraints(
					temporalRanges,
					"ignored"); // the name is not used in this case

			final QueryOptions options = new QueryOptions(
					adapterId,
					indexId);
			final CompareOperation op = CompareOperation.valueOf(
					request.getCompareOperation());
			final SpatialTemporalQuery spatialTemporalQuery = new SpatialTemporalQuery(
					temporalConstraints,
					queryGeom,
					op);

			try (final CloseableIterator<SimpleFeature> iterator = dataStore.query(
					options,
					spatialTemporalQuery)) {
				while (iterator.hasNext()) {
					final SimpleFeature simpleFeature = iterator.next();
					final SimpleFeatureType type = simpleFeature.getType();
					final Feature.Builder b = Feature.newBuilder();
					for (int i = 0; i < type.getAttributeDescriptors().size(); i++) {
						b.putAttributes(
								type
										.getAttributeDescriptors()
										.get(
												i)
										.getLocalName(),
								simpleFeature.getAttribute(
										i) == null ? ""
												: simpleFeature
														.getAttribute(
																i)
														.toString());
					}
					final Feature f = b.build();
					responseObserver.onNext(
							f);
				}
				responseObserver.onCompleted();
			}
			catch (final IOException e) {
				LOGGER.error(
						"Exception encountered closing iterator",
						e);
			}
		}
	}
}
