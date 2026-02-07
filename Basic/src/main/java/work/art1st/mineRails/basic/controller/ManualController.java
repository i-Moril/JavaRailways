package work.art1st.mineRails.basic.controller;

import lombok.NonNull;
import work.art1st.mineRails.core.control.train.TrainController;
import work.art1st.mineRails.core.physics.World;
import work.art1st.mineRails.core.physics.train.Train;

public class ManualController extends TrainController {
    public ManualController(@NonNull Train train, @NonNull World world) {
        super(train, world);
    }

    public void setSpeed(double speed) {

    }

    @Override
    public void tick(double deltaT) {

    }
}
