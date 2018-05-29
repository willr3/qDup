package perf.qdup.cmd.impl;

import perf.qdup.cmd.Cmd;
import perf.qdup.cmd.Context;
import perf.qdup.cmd.CommandResult;

public class Signal extends Cmd {
    private String name;
    public Signal(String name){ this.name = name;}
    public String getName(){return name;}
    @Override
    public void run(String input, Context context, CommandResult result) {
        String populatedName = Cmd.populateStateVariables(name,this,context.getState());
        context.getCoordinator().signal(populatedName);
        result.next(this,input);
    }

    @Override
    public Cmd copy() {
        return new Signal(this.name);
    }

    @Override public String toString(){return "signal: "+name;}
}
