package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.Run;
import io.hyperfoil.tools.qdup.SshTestBase;
import io.hyperfoil.tools.qdup.State;
import io.hyperfoil.tools.qdup.cmd.Dispatcher;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class CountdownTest extends SshTestBase {
    @Test
    public void no_name_same_script_collision_check(){
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("pwd", stream("" +
                "scripts:",
                "  foo:",
                "  - set-signal: stop 1",//otherwise qDup calculates 2 expected signals
                "  - repeat-until: stop",
                "    then:",
                "    - set-state: RUN.${{name}} ${{=${{RUN.${{name}}:0}}+1}}",
                "    - countdown: 10",
                "      then:",
                "      - set-state: RUN.final-${{name}} ${{RUN.${{name}}:0}}",
                "      - sleep: 1s",
                "      - signal: stop",
                "hosts:",
                "  local: " + getHost(),
                "roles:",
                "  doit:",
                "    hosts: [local]",
                "    run-scripts:",
                "    - foo:",
                "        with:",
                "          name: uno",
                "    - foo:",
                "        with:",
                "          name: dos"
        )));
        RunConfig config = builder.buildConfig(parser);
        assertFalse("runConfig errors:\n" + config.getErrorStrings().stream().collect(Collectors.joining("\n")), config.hasErrors());
        Dispatcher dispatcher = new Dispatcher();
        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        State state = config.getState();
        assertTrue("state.uno should exist",state.has("uno"));
        assertTrue("state.dos should exist",state.has("dos"));
        assertEquals("state.uno",10l,state.get("uno"));
        assertEquals("state.dos",10l,state.get("dos"));
        assertTrue("state.final-uno should exist",state.has("final-uno"));
        assertTrue("state.final-dos should exist",state.has("final-dos"));
        assertEquals("state.final-uno",10l,state.get("final-uno"));
        assertEquals("state.final-dos",10l,state.get("final-dos"));
    }
}
