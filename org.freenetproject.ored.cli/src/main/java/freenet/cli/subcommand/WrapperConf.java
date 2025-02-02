/*
 * Copyright 2022 Marine Master
 *
 * This file is part of Oldenet.
 *
 * Oldenet is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * Oldenet is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Oldenet.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package freenet.cli.subcommand;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

import freenet.cli.mixin.IniPathOption;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "wrapperconf", description = "Create a custom configuration file for Wrapper")
public class WrapperConf implements Callable<Integer> {

	@CommandLine.Mixin
	private IniPathOption iniPathOptionMixin;

	@Parameters(paramLabel = "FILE", description = "File path to save the custom configuration file")
	File customConfFile;

	@Override
	public Integer call() throws Exception {
		try (var writer = Files.newBufferedWriter(this.customConfFile.toPath(), StandardOpenOption.CREATE,
				StandardOpenOption.WRITE)) {
			writer.append("wrapper.app.parameter.1=\"");
			writer.append(this.iniPathOptionMixin.iniPath.toString());
			writer.append("\"\n");
			writer.append("wrapper.logfile=");
			writer.append(this.iniPathOptionMixin.iniPath.getParent().resolve("logs/wrapper.log").toString());
			writer.append("\n");
		}
		return 0;
	}

}
