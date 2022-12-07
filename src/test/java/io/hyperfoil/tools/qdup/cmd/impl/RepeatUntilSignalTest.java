package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.Run;
import io.hyperfoil.tools.qdup.SshTestBase;
import io.hyperfoil.tools.qdup.State;
import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.Dispatcher;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RepeatUntilSignalTest extends SshTestBase {

    @Test
    public void selfSignaling(){
        RepeatUntilSignal repeatUntilSignal = new RepeatUntilSignal("foo");
        repeatUntilSignal.then(Cmd.sleep("1s").then(Cmd.signal("foo")));
        Assert.assertTrue("Should count as self signaling\n"+repeatUntilSignal.tree(2,true),repeatUntilSignal.isSelfSignaling());
    }

    @Test
    public void selfSignaling_regex_onMiss(){
        RepeatUntilSignal repeatUntilSignal = new RepeatUntilSignal("foo");
        Regex regex = new Regex(".*");
        regex.onElse(Cmd.signal("foo"));
        repeatUntilSignal.then(regex);

        Assert.assertTrue("Should count as self signaling\n"+repeatUntilSignal.tree(2,true),repeatUntilSignal.isSelfSignaling());
    }

    @Test
    public void nextAndSkipTest(){
        Cmd start = Cmd.NO_OP();
        start.then(
            Cmd.repeatUntil("FOO")
                .then(Cmd.sh("1"))
                .then(Cmd.sh("2"))
        )
        .then(Cmd.sh("3"));
        Cmd repeat = start.getNext();

        Cmd one = repeat.getNext();
        Cmd two = one.getNext();

        Assert.assertEquals("2.next should be repeat",true,two.getNext().toString().contains("FOO"));
        Assert.assertEquals("repeat.skip should be 3",true,repeat.getSkip().toString().contains("3"));
    }

    @Test(timeout = 15_000)
    public void timer_during_child(){
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("pwd", stream("" +
            "scripts:",
            "  foo:",
            "    - set-state: RUN.count 0",
            "    - repeat-until: foo",
            "      then:",
            "      - sleep: 2s",
            "      - set-state: RUN.count ${{=${{RUN.count:0}}+1}}",
            "      timer:",
            "        1s:",
            "        - set-state: RUN.ran ${{=${{RUN.ran:0}}+1}}",
            "        - signal: foo",
            "hosts:",
            "  local: " + getHost(),
            "roles:",
            "  doit:",
            "    hosts: [local]",
            "    run-scripts: [foo]"
        )));
        RunConfig config = builder.buildConfig(parser);
        assertFalse("runConfig errors:\n" + config.getErrorStrings().stream().collect(Collectors.joining("\n")), config.hasErrors());
        Dispatcher dispatcher = new Dispatcher();
        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        State state = config.getState();
        assertTrue("state should have count",state.has("count"));
        assertEquals("state.count should be 1",1l,state.get("count"));
        assertEquals("state.ran should be 1",1l,state.get("ran"));
    }
    @Test(timeout = 15_000)
    public void timer_not_once_per_loop(){
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("pwd", stream("" +
            "scripts:",
            "  foo:",
            "    - set-state: RUN.count 0",
            "    - repeat-until: foo",
            "      then:",
            "      - sleep: 1s",
            "      - set-state: RUN.count ${{=${{RUN.count:0}}+1}}",
            "      timer:",
            "        3s:",
            "        - set-state: RUN.ran ${{=${{RUN.ran:0}}+1}}",
            "        - signal: foo",
            "hosts:",
            "  local: " + getHost(),
            "roles:",
            "  doit:",
            "    hosts: [local]",
            "    run-scripts: [foo]"
        )));
        RunConfig config = builder.buildConfig(parser);
        assertFalse("runConfig errors:\n" + config.getErrorStrings().stream().collect(Collectors.joining("\n")), config.hasErrors());
        Dispatcher dispatcher = new Dispatcher();
        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        try {//to see if other timers complete
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        State state = config.getState();
        assertTrue("state should have count",state.has("count"));
        assertEquals("state.ran should be 1",1l,state.get("ran"));
    }
}
