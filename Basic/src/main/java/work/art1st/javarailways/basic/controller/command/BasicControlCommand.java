package work.art1st.javarailways.basic.controller.command;

import lombok.Getter;
import work.art1st.javarailways.core.api.control.ControlCommand;

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
