package io.hyperfoil.tools.qdup.config.rule;

import io.hyperfoil.tools.qdup.SshTestBase;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.RunSummary;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import org.junit.Test;

import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class CountdownCollisionTest extends SshTestBase {

    @Test
    public void duplicate_names_same_script(){
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = new RunConfigBuilder();
        builder.loadYaml(parser.loadFile("signal",stream(""+
            "scripts:",
            "  test:",
            "    - countdown: foo 1",
            "    - countdown: foo 2",
            "hosts:",
            "  local: me@localhost",
            "roles:",
            "  role:",
            "    hosts: [local]",
            "    run-scripts:",
            "    - test"
        )));
        RunConfig config = builder.buildConfig(parser);
        RunSummary summary = new RunSummary();
        CountdownCollission countdownCollission = new CountdownCollission();
        summary.addRule("countdownCollision",countdownCollission);
        summary.scan(config.getRolesValues(),builder);
        assertTrue("expected errors:\n"+summary.getErrors().stream().map(Objects::toString).collect(Collectors.joining("\n")),summary.hasErrors());
    }
    @Test
    public void duplicate_names_same_script_same_intial(){
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = new RunConfigBuilder();
        builder.loadYaml(parser.loadFile("signal",stream(""+
                "scripts:",
                "  test:",
                "    - countdown: foo 1",
                "    - countdown: foo 1",
                "hosts:",
                "  local: me@localhost",
                "roles:",
                "  role:",
                "    hosts: [local]",
                "    run-scripts:",
                "    - test"
        )));
        RunConfig config = builder.buildConfig(parser);
        RunSummary summary = new RunSummary();
        CountdownCollission countdownCollission = new CountdownCollission();
        summary.addRule("countdownCollision",countdownCollission);
        summary.scan(config.getRolesValues(),builder);
        assertFalse("expected errors:\n"+summary.getErrors().stream().map(Objects::toString).collect(Collectors.joining("\n")),summary.hasErrors());
    }
}
