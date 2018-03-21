package mil.nga.giat.geowave.service.grpc.cli;

import com.beust.jcommander.Parameter;

public class RunGrpcOptions
{
	@Parameter(names = {
		"-p",
		"--port"
	}, required = false, description = "The port to run on")
	private Integer port = 8980;

	public Integer getPort() {
		return port;
	}

}
