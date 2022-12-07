package io.hyperfoil.tools.qdup.config.rule;

import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.impl.Countdown;
import io.hyperfoil.tools.qdup.cmd.impl.WaitFor;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.RunRule;
import io.hyperfoil.tools.qdup.config.RunSummary;

import java.util.HashMap;
import java.util.Map;

public class CountdownCollission implements RunRule {

    private Map<String,Integer> counters;

    public CountdownCollission(){
        counters = new HashMap<>();
    }

    @Override
    public void scan(CmdLocation location, Cmd command, Cmd.Ref ref, RunConfigBuilder config, RunSummary summary) {
        if(command instanceof Countdown){
            Countdown countdown = (Countdown) command;
            if(countdown.hasName()){
                String name = Cmd.populateStateVariables(countdown.getName(), command, config.getState(), null, null, ref);
                if(name.contains(command.getPatternPrefix())){
                    //TODO do we warn of imprecise name collision detection?
                }
                if(! counters.containsKey(name) ){
                    counters.put(name,countdown.getInitial());
                }else{
                    int initial = counters.get(name);
                    if(initial != countdown.getInitial()){
                        summary.addError(location.getRoleName(), location.getStage(),location.getScriptName(),command.toString(),
                                name+" is used with different initial values: "+initial+" and "+countdown.getInitial());
                    }
                }
            }
        }
    }

    @Override
    public void close(RunConfigBuilder config, RunSummary summary) {

    }
}
