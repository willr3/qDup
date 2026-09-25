package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.Context;

//SIGINT
public class CtrlC extends CtrlSignal {
    public CtrlC(){super("ctrlC",'C');}

    @Override
    public Cmd copy() {
        return new CtrlC();
    }

    @Override
    public void run(String input, Context context) {
        context.setInterrupted(true);
        super.run(input, context);
    }
}