package work.art1st.mineRails.basic.controller.command;

import lombok.Getter;
import work.art1st.mineRails.core.control.command.ControlCommand;

public abstract class BasicControlCommand implements ControlCommand {
    private double tick = 0.0;
    @Getter
    private double deltaT = 0.0;

    @Override
    public void tick(double deltaT) {
        this.tick += deltaT;
        this.deltaT = deltaT;
    }

    protected double getTick() {
        return this.tick;
    }
}
