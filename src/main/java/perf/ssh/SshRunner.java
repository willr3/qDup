package perf.ssh;

import org.apache.commons.cli.*;
import perf.ssh.cmd.CommandDispatcher;
import perf.ssh.config.YamlLoader;
import perf.util.StringUtil;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SshRunner {

    public static void main(String[] args) {

        Options options = new Options();

        Option basePath = Option.builder("b")
            .longOpt("basePath")
            .required()
            .hasArg()
            .argName("path")
            .desc("base path for the output folder")
            .build();
        basePath.setRequired(true);
        options.addOption(basePath);

        Option commandPool = Option.builder("c")
            .longOpt("commandPool")
            .hasArg()
            .argName("size")
            .type(Integer.TYPE)
            .desc("number of threads for executing commands (default 24)")
            .build();
        options.addOption(commandPool);

        Option scheduledPool = Option.builder("s")
            .longOpt("scheduledPool")
            .hasArg()
            .argName("size")
            .type(Integer.TYPE)
            .desc("number of threads for executing scheduled tasks (default 4)")
            .build();
        options.addOption(scheduledPool);

        Option state = Option.builder("S")
            .argName("key=value")
            .desc("set a state parameter")
            .hasArgs()
            .valueSeparator()
            .build();
        options.addOption(state);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        URL url = ClassLoader.getSystemResource("specjms.yaml");

        try{
            cmd = parser.parse(options,args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("[options] [yaml files]",options);
            System.exit(1);
            return;
        }

        String path = cmd.getOptionValue("basePath");
        int commandThreads = Integer.parseInt(cmd.getOptionValue("commandPool","24"));
        int scheduledThreads = Integer.parseInt(cmd.getOptionValue("scheduledPool","4"));

        List<String> yamlPaths = cmd.getArgList();

        if(yamlPaths.isEmpty()){
            System.out.println("Missing required yaml file(s)");
            formatter.printHelp("[options] [yaml files]",options);
            System.exit(1);
            return;
        }

        YamlLoader loader = new YamlLoader();
        for(String yamlPath : yamlPaths){
            System.out.println("loading: "+yamlPath);
            loader.load(yamlPath);
        }
        if(loader.hasErrors()){
            for(String error : loader.getErrors()){
                System.out.println("Error: "+error);
            }
            System.exit(1);
            return;
        }

        RunConfig config = loader.getRunConfig();

        Properties stateProps = cmd.getOptionProperties("S");
        if(!stateProps.isEmpty()){
            System.out.println("Setting custom state:");

            stateProps.forEach((k,v)->{
                System.out.println("  "+k+" = "+v);
                config.getState().set(k.toString(),v.toString());
            });


        }

        final AtomicInteger factoryCounter = new AtomicInteger(0);
        final AtomicInteger scheduledCounter = new AtomicInteger(0);

        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();

        ThreadFactory factory = r -> new Thread(r,"command-"+factoryCounter.getAndIncrement());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(8,24,30, TimeUnit.MINUTES,workQueue,factory);
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(24, runnable -> new Thread(runnable,"scheduled-"+scheduledCounter.getAndIncrement()));

        CommandDispatcher dispatcher = new CommandDispatcher(executor,scheduled);

        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        Run run = new Run("/home/wreicher/perfWork/amq/jdbc/"+dt.format(LocalDateTime.now()),config,dispatcher);

        System.out.println("Starting with output path = "+run.getOutputPath());

        long start = System.currentTimeMillis();

        run.run();

        long stop = System.currentTimeMillis();

        System.out.println("Finished in "+ StringUtil.durationToString(stop-start));

        dispatcher.shutdown();
        executor.shutdownNow();
        scheduled.shutdownNow();

    }
}
