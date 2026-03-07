package work.art1st.mineRails.basic.controller;

import lombok.NonNull;
import work.art1st.mineRails.core.control.train.TrainController;
import work.art1st.mineRails.core.physics.CoreWorld;
import work.art1st.mineRails.core.physics.train.CoreTrain;

public class ManualController extends TrainController {
    public ManualController(@NonNull CoreTrain train, @NonNull CoreWorld world) {
        super(train, world);
    }

    public void setSpeed(double speed) {

    }

    @Override
    public void tick(double deltaT) {

    }
}
