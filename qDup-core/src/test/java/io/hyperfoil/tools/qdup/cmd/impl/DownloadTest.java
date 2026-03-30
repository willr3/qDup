package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.*;
import io.hyperfoil.tools.qdup.cmd.Dispatcher;
import io.hyperfoil.tools.qdup.cmd.ScriptContext;
import io.hyperfoil.tools.qdup.cmd.SpyContext;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import io.hyperfoil.tools.qdup.shell.AbstractShell;
import io.hyperfoil.tools.qdup.shell.LocalShell;
import io.hyperfoil.tools.yaup.time.SystemTimer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class DownloadTest extends SshTestBase{

    @Test
    public void return_local_file_path_when_defined_without_destination() throws IOException {
        String wrote = "bizbuz";
        File source = Files.createTempFile("qdup","txt").toFile();
        Files.write(source.toPath(),wrote.getBytes());
        File destinationFolder = Files.createTempDirectory("qdup").toFile();
        Download d = new Download(source.getPath());

        AbstractShell localShell = AbstractShell.getShell(
                "return_local_file_path_when_defined_without_destination",
                Host.parse(Host.LOCAL),
                new ScheduledThreadPoolExecutor(2),
                new SecretFilter(),
                null
        );
        Run run = new Run(destinationFolder.getPath(),new RunConfigBuilder().buildConfig(),new Dispatcher());
        ScriptContext scriptContext = new ScriptContext(localShell,run.getConfig().getState(),run,new SystemTimer("download"),d,true);
        SpyContext spyContext = new SpyContext(scriptContext,run.getConfig().getState(), run.getCoordinator());
        d.run("",spyContext);

        assertNotNull("download should call next when download succeeds",spyContext.getNext());

        String result = spyContext.getNext();
        File resultFile = new File(result);

        assertTrue("result file should exist",resultFile.exists());
        assertTrue("download should return path starting with destinationFolder",spyContext.getNext().startsWith(destinationFolder.getPath()));
        assertTrue("download return should end with source name\nreturned "+spyContext.getNext()+"\nsource "+source.getName(),spyContext.getNext().endsWith(source.getName()));

        String read = Files.readString(resultFile.toPath());
        assertEquals(read,wrote);

    }

    @Test
    public void observing_relative_home_alias_and_environment_reference_exit_code() throws IOException {

        Parser parser = Parser.getInstance();
        parser.setAbortOnExitCode(true);
        RunConfigBuilder builder = getBuilder();

        builder.loadYaml(parser.loadFile("pwd",
                """
                scripts:
                  foo:
                   - sh: export NAME=tres
                   - sh: export FOLDER=two
                   - sh: mkdir -p ~/foo/one
                   - sh: mkdir -p /tmp/foo/two
                   - sh: echo 'uno' >> ~/foo/one/uno.txt
                   - sh: echo 'dos' >> /tmp/foo/two/dos.txt
                   - sh: echo 'tres' >> /tmp/foo/two/tres.txt
                   - sh: cd /tmp/foo
                   - sh:
                       command: sleep 4s; (exit 42);
                       ignore-exit-code: true
                     timer:
                       1s:
                         - download: ~/foo/one/uno.txt
                         - download: ./two/dos.txt
                         - download: /tmp/foo/$FOLDER/${NAME}.txt
                   - download: ~/foo/one/uno.txt uno2.txt
                   - download: ./two/dos.txt dos2.txt
                   - download: /tmp/foo/$FOLDER/${NAME}.txt name2.txt
                   - sh: echo $?
                     then:
                     - set-state: RUN.ec
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));
        RunConfig config = builder.buildConfig(parser);
        assertFalse("runConfig errors:\n" + config.getErrorStrings().stream().collect(Collectors.joining("\n")), config.hasErrors());
        Dispatcher dispatcher = new Dispatcher();
        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.ensureConsoleLogging();
        doit.run();

        Host host = config.getAllHostsInRoles().iterator().next();

        State state = doit.getConfig().getState();

        File uno = new File(tmpDir.toString() + "/"+host.getShortHostName()+"/uno.txt");
        File dos = new File(tmpDir.toString() + "/"+host.getShortHostName()+"/dos.txt");
        File tres = new File(tmpDir.toString() + "/"+host.getShortHostName()+"/tres.txt");

        assertTrue("uno should exist @ "+uno.getPath(),uno.exists());
        assertTrue("dos should exist @ "+dos.getPath(),dos.exists());
        assertTrue("tres should exist @ "+tres.getPath(),tres.exists());

        assertTrue("ec should be set",state.has("ec"));
        assertEquals(42L,state.get("ec"));


        String content = Files.readString(Paths.get(doit.getOutputPath(),"run.json"));
    }
}
