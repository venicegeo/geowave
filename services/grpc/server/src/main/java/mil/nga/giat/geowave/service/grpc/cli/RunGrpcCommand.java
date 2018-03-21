package mil.nga.giat.geowave.service.grpc.cli;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameters;

import mil.nga.giat.geowave.core.cli.annotations.GeowaveOperation;
import mil.nga.giat.geowave.core.cli.api.Command;
import mil.nga.giat.geowave.core.cli.api.DefaultOperation;
import mil.nga.giat.geowave.core.cli.api.OperationParams;
import mil.nga.giat.geowave.datastore.hbase.cli.HBaseMiniCluster;
import mil.nga.giat.geowave.datastore.hbase.cli.HBaseSection;
import mil.nga.giat.geowave.service.grpc.GeoWaveGrpcServer;

@GeowaveOperation(name = "run", parentOperation = GrpcSection.class)
@Parameters(commandDescription = "Runs a gRPC service for GeoWave commands")
public class RunGrpcCommand extends
		DefaultOperation implements
		Command
{
	private static final Logger LOGGER = LoggerFactory.getLogger(RunGrpcCommand.class);
	private RunGrpcOptions options;
	/**
	 * Prep the driver & run the operation.
	 */
	@Override
	public void execute(
			final OperationParams params ) {
		try {
			LOGGER.info(
					"Starting server");
			GeoWaveGrpcServer server = null;

			try {
				server = new GeoWaveGrpcServer(
						options.getPort());
			}
			catch (final IOException e) {
				LOGGER.error(
						"Exception encountered instantiating gRPC server",
						e);
			}

			try {
				server.start();
				server.blockUntilShutdown();
			}
			catch (final IOException | NullPointerException e) {
				LOGGER.error(
						"Exception encountered starting gRPC server",
						e);
			}
		}
		catch (final Exception e) {
			LOGGER.error(
					"Unable to run HBase mini cluster",
					e);
		}
	}
}