package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.JsonServer;
import io.hyperfoil.tools.qdup.Run;
import io.hyperfoil.tools.qdup.State;
import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.Dispatcher;
import io.hyperfoil.tools.qdup.cmd.Result;
import io.hyperfoil.tools.qdup.cmd.Script;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import io.vertx.core.Vertx;
import org.junit.Ignore;
import org.junit.Test;
import io.hyperfoil.tools.qdup.SshTestBase;
import io.hyperfoil.tools.qdup.cmd.SpyContext;
import io.hyperfoil.tools.yaup.json.Json;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RegexTest extends SshTestBase {

    @Test
    public void embedded_dot_all(){
        Regex regex = new Regex("(?s)cat=(?<cat>.+)");
        SpyContext spyContext = new SpyContext();
        regex.run("cat=uno\ndos",spyContext);
        Object found = spyContext.getState().get("cat");
        assertNotNull("state should contain an entry for cat",found);
        assertEquals("uno\ndos",found.toString());
    }

    @Test
    public void multiple_appearances(){
        Regex regex = new Regex("cat=(?<cat>.+)");
        SpyContext spyContext = new SpyContext();
        regex.run("cat=uno\ncat=dos",spyContext);
        Object found = spyContext.getState().get("cat");
        assertNotNull("state should contain an entry for cat",found);
        assertEquals("uno",found.toString());
    }

    @Test
    public void regex_dot_star_with_newline(){
        Regex regex = new Regex(" link currently points to (?<java_home>/.*?)(?:/jre)?/bin/java");

        SpyContext spyContext = new SpyContext();

        regex.run("alternatives --display java\n" +
                "java - status is manual.\n" +
                " link currently points to /usr/lib/jvm/java-11-openjdk-11.0.10.0.9-4.el8_3.x86_64/bin/java\n" +
                "/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.282.b08-2.el8_3.x86_64/jre/bin/java - family java-1.8.0-openjdk.x86_64 priority 1800282",
                spyContext);

        Object found = spyContext.getState().get("java_home");
        assertNotNull("state should have java_home\n"+spyContext.getState().tree(),found);
        assertEquals("/usr/lib/jvm/java-11-openjdk-11.0.10.0.9-4.el8_3.x86_64",found.toString());
    }

    @Test
    public void else_count() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - regex: "Red Hat Enterprise Linux CoreOS"
                    then:
                    - log: connected to ${{host.ip}}
                    - signal: ${{host.name}}-connected
                    - sh: exit #exit the ssh to the worker
                    else:
                    - log: failed to connect to ${{host.ip}}
                    - sleep: 2m #only sleep if we didn't match
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                  data: "miss"
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);

        Script foo = config.getScript("foo");

        assertNotNull(foo);
        assertTrue(foo.hasThens());
        Cmd then = foo.getThens().get(0);
        assertTrue(then instanceof Regex);
        Regex regex = (Regex) then.copy();
    }

    @Test
    public void else_previous() {
        Regex parent = new Regex("FOO");
        Regex child = new Regex("BAR");
        parent.onElse(child);

        Cmd previous = child.getPrevious();

        assertEquals("onMiss previous should be parent", parent, previous);

    }

    @Test
    public void case_insensitive_match_true_or_yes() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - read-state: ${{data}}
                  - regex: "(?i)true|yes"
                    then:
                    - set-state: RUN.regex MATCHED
                    else:
                    - set-state: RUN.regex MISS
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                  data: "True"
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);

        Dispatcher dispatcher = new Dispatcher();

        List<String> signals = new ArrayList<>();

        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        dispatcher.shutdown();

        State state = config.getState();

        assertTrue("state should have regex", state.has("regex"));
        assertEquals("regex should be MISS", "MATCHED", state.getString("regex"));

    }

    @Test
    public void docs_howto_secrets_regex_capture() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - sh: echo password
                  - regex: "(?<_my-secret>.*)"
                    then:
                    - set-state: RUN.regex ${{my-secret}}
                    else:
                    - set-state: RUN.regex MISS
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                  data: "True"
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);

        Dispatcher dispatcher = new Dispatcher();

        List<String> signals = new ArrayList<>();

        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        dispatcher.shutdown();

        State state = config.getState();
        assertTrue("state should have regex", state.has("regex"));


    }


    @Test
    public void timer_resolve_with_reference() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - read-state: ${{data}}
                  - regex: match
                    then:
                    - set-state: RUN.regex MATCHED
                    else:
                    - set-state: RUN.regex MISS
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                  data: "miss"
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);

        Dispatcher dispatcher = new Dispatcher();

        List<String> signals = new ArrayList<>();

        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        dispatcher.shutdown();

        State state = config.getState();

        assertTrue("state should have regex", state.has("regex"));
        assertEquals("regex should be MISS", "MISS", state.getString("regex"));

    }

    @Test
    public void override_foreach_with_regex_state() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - for-each: foo ["uno/one"]
                    then:
                    - set-state: RUN.before ${{foo}}
                    - regex: "uno/(?<foo>.*)"
                      then:
                      - set-state: RUN.during ${{foo}}
                    - set-state: RUN.after ${{foo}}
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);
        Dispatcher dispatcher = new Dispatcher();
        List<String> signals = new ArrayList<>();
        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        dispatcher.shutdown();

        State state = config.getState();

        assertEquals("uno/one",state.getString("before"));
        assertEquals("one",state.getString("during"));//regex does not override for-each because it uses with instead of state
        assertEquals("uno/one",state.getString("after"));

    }

    @Test @Ignore
    public void debug_alternatives() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - sh: alternatives --display java
                  - regex: " link currently points to (?<RUN.java_home>/.*?)(?:/jre)?/bin/java"
                    then:
                    - set-state: RUN.found true
                    else:
                    - set-state: RUN.found false
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                states:
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));

        RunConfig config = builder.buildConfig(parser);

        Dispatcher dispatcher = new Dispatcher();

        List<String> signals = new ArrayList<>();

        Run doit = new Run(tmpDir.toString(), config, dispatcher);
        doit.run();
        dispatcher.shutdown();

        State state = config.getState();

        assertTrue("state should have found", state.has("found"));

    }



    @Test
    public void regex_template_in_pattern() {
        Regex regex = new Regex("${{host.ip}} .*? \"GET /${{raw_image}} HTTP/1.1\" 200 -");
        regex.with(Json.fromString("{\"host\":{\"ip\":\"192.168.0.100\"}}"));
        regex.with("raw_image", "rhcos-4.5.2-x86_64-metal.x86_64.raw.gz");

        SpyContext context = new SpyContext();

        regex.run("192.168.0.100 - - [11/Aug/2020 17:54:10] \"GET /rhcos-4.5.2-x86_64-metal.x86_64.raw.gz HTTP/1.1\" 200 -\n", context);

        assertTrue("regex should call net", context.hasNext());
    }

    @Test
    public void regex_match_pattern() {
        Regex regex = new Regex("^\\s*auth_tcp\\s*=\\s*\"none\"");

        SpyContext context = new SpyContext();

        regex.run("#auth_tcp=\"none\"", context);

        assertFalse("regex should not call next", context.hasNext());
        assertTrue("regex should call skip", context.hasSkip());
    }

    @Test
    public void getNext_isMiss_onMiss_misses() {
        Cmd regex = new Regex("foo", true).onElse(Cmd.log("miss"));
        regex.then(Cmd.log("matches"));

        SpyContext context = new SpyContext();

        regex.run("bar", context);
        Cmd next = regex.getNext();

        assertTrue("context should have called next", context.hasNext());
        assertEquals("context should have called next", "bar", context.getNext());
        assertNotNull("next should not be null", next);
        assertTrue("next should be a log command", next instanceof Log);
        Log log = (Log) next;
        assertTrue("next should log matches", log.getMessage().contains("matches"));
    }

    @Test
    public void getNext_onMiss_misses() {
        Cmd regex = new Regex("foo").onElse(Cmd.log("miss"));
        regex.then(Cmd.log("matches"));

        SpyContext context = new SpyContext();


        regex.run("bar", context);

        Cmd next = regex.getNext();

        assertTrue("context should have called next " + context, context.hasNext());
        assertEquals("context should have called next " + context, "bar", context.getNext());
        assertNotNull("next should not be null " + context, next);
        assertTrue("next should be a log command " + context, next instanceof Log);
        Log log = (Log) next;
        assertTrue("next should log miss " + log.getMessage(), log.getMessage().contains("miss"));
    }

    @Test
    public void getNext_onMiss_matches() {
        Cmd regex = new Regex("foo").onElse(Cmd.log("miss"));
        regex.then(Cmd.log("matches"));

        SpyContext context = new SpyContext();

        regex.run("foo", context);
        Cmd next = regex.getNext();

        assertTrue("context should have called next", context.hasNext());
        assertEquals("context should have called next", "foo", context.getNext());
        assertNotNull("next should not be null", next);
        assertTrue("next should be a log command", next instanceof Log);
        Log log = (Log) next;
        assertTrue("next should log miss", log.getMessage().contains("matches"));
    }


    @Test
    @Ignore
    public void systemctlBug() {
        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        StringBuilder sb = new StringBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - sh: sudo systemctl status docker
                    - regex: "\\s*Active: (?<active>\\w+) \\(.*" #Test to see if docker is running
                      - log: active=${{active}}
                hosts:
                  local: root@benchclient1.perf.lab.eng.rdu2.redhat.com:22"
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                """
        ));
        RunConfig config = builder.buildConfig(parser);
        Dispatcher dispatcher = new Dispatcher();

        Cmd foo = config.getScript("foo");
        foo.getNext().getNext().injectThen(Cmd.code(((input, state) -> {
            return Result.next(input);
        })));

        Run doit = new Run(tmpDir.toString(), config, dispatcher);

        JsonServer jsonServer = new JsonServer(Vertx.vertx(), doit);
        jsonServer.start();
        doit.run();
        dispatcher.shutdown();
    }

    @Test
    public void regex_ip_pattern() {
        Cmd regex = Cmd.regex("(?<ip>\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");
        SpyContext context = new SpyContext();
        regex.run("192.168.0.1", context);
        assertEquals("capture ip", "192.168.0.1", context.getState().get("ip"));
    }

    @Test
    public void one_line_in_multi_line() {
        Cmd regex = Cmd.regex("(?<date>\\d{4}-\\d{2}-\\d{2})\\s+(?<time>\\d{2}:\\d{2}:\\d{2})\\s+(?<offset>[+-]\\d{4})");
        SpyContext context = new SpyContext();
        regex.run(
                "fatal: unable to read source tree (ea9f40f5940637b18c197952e7d0bd0a28185ae9)"
                        + "\n" + "2019-10-01 16:21:04 -1000",
                context);
        assertEquals("capture date from multi-line pattern", "2019-10-01", context.getState().get("date"));
    }

    @Test
    public void lineEnding() {
        Cmd regex = Cmd.regex("^SUCCESS$");
        SpyContext context = new SpyContext();

        context.clear();
        regex.run("SUCCESS", context);

        assertEquals("next should match entire pattern", "SUCCESS", context.getNext());
        assertNull("regex should match", context.getSkip());
    }

    @Test
    public void named_capture() {
        Cmd regex = Cmd.regex("(?<all>.*)");
        SpyContext context = new SpyContext();
        context.clear();
        regex.run("foo", context);

        assertEquals("state.get(all) should be foo", "foo", context.getState().get("all"));
    }

    @Test
    public void named_capture_with_dots() {
        Cmd regex = Cmd.regex("(?<all.with.dots>.*)");
        SpyContext context = new SpyContext();
        context.clear();
        regex.run("foo", context);

        assertEquals("state.get(all.with.dots) should be foo", "foo", context.getState().get("all.with.dots"));
        Object all = context.getState().get("all");
        assertTrue("state.get(all) should return json", all instanceof Json);
    }
    @Test
    public void named_capture_with_underscore_dash() {
        Cmd regex = Cmd.regex("(?<all_underscore-dash>.*)");
        SpyContext context = new SpyContext();
        context.clear();
        regex.run("foo", context);

        assertEquals("state.get(all_underscore-dash) should be foo", "foo", context.getState().get("all_underscore-dash"));
    }



    @Test
    @Ignore
    public void removeDoubleSlashedRegex() {

        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("regex",
                """
                scripts:
                  foo:
                  - regex: ".*? WFLYSRV0025: (?<eapVersion>.*?) started in (?<eapStartTime>\\\\d+)ms.*"
                """
        ));

        Script foo = builder.buildConfig(parser).getScript("foo");

        Cmd regex = foo.getNext();

//        Cmd built = builder.buildYamlCommand(parser.getJson("regex"), null, errors);
//        assertTrue("built should be Regex:" + builder.getClass(), built instanceof Regex);
//        Regex regex = (Regex) built;
//        assertFalse("should not contain \\\\\\\\", regex.getPattern().contains("\\\\\\\\"));
//        assertTrue("should contain \\d", regex.getPattern().contains("\\d"));
    }

    @Test
    public void capture_group_auto_convert() {
        Cmd regex = Cmd.regex("Please open the following file: /tmp/perf-test/build/reports/gatling/(?<framework>.*)-(?<timestamp>\\d*)/index.html", false);
        SpyContext context = new SpyContext();
        regex.run(
                "Please open the following file: /tmp/perf-test/build/reports/gatling/quarkus-20210307110018725/index.html",
                context);
        assertEquals("capture framework from pattern", "quarkus", context.getState().get("framework"));
        assertEquals("capture timestamp from pattern", "20210307110018725", context.getState().get("timestamp"));


        Parser parser = Parser.getInstance();
        RunConfigBuilder builder = getBuilder();
        builder.loadYaml(parser.loadFile("",
                """
                scripts:
                  foo:
                  - sh: "echo 'Please open the following file: /tmp/perf-test/build/reports/gatling/quarkussimulation-20210307110018725/index.html'"
                    then:
                    - regex:
                        pattern: "Please open the following file: /tmp/perf-test/build/reports/gatling/(?<framework>.*)-(?<timestamp>\\\\d*)/index.html"
                        autoConvert: false
                      then:
                      - set-state:
                          key: RUN.TIMESTAMP
                          value: ${{timestamp}}
                          autoConvert: false
                hosts:
                  local: TARGET_HOST
                roles:
                  doit:
                    hosts: [local]
                    run-scripts: [foo]
                """.replaceAll("TARGET_HOST",getHost().toString())
        ));
        RunConfig config = builder.buildConfig(parser);
        Dispatcher dispatcher = new Dispatcher();

        Run doit = new Run(tmpDir.toString(), config, dispatcher);

        doit.run();
        dispatcher.shutdown();

        State state = config.getState();

        assertTrue("state should have timestamp", state.has("TIMESTAMP"));
        assertEquals("timestamp should be 20210307110018725", "20210307110018725", state.getString("TIMESTAMP"));

    }

}
