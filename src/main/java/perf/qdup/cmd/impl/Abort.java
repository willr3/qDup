package perf.qdup.cmd.impl;

import perf.qdup.cmd.Cmd;
import perf.qdup.cmd.Context;
import perf.qdup.cmd.CommandResult;

public class Abort extends Cmd {
    private String message;
    public Abort(String message){
        this.message = message;
    }

    @Override
    public void run(String input, Context context, CommandResult result) {
        String populatedMessage = Cmd.populateStateVariables(message,this,context.getState());
        context.getRunLogger().info("abort {}",populatedMessage);
        context.abort();

        //result.next(this,input);
    }

    public String getMessage(){return message;}

    @Override
    public Cmd copy() {
        return new Abort(this.message);
    }

    @Override
    public String toString(){return "abort: "+this.message;}
}
