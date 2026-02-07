package work.art1st.mineRails.basic.controller.command;

public class AccelerateCommand extends BasicControlCommand {
    protected final double jerk;
    protected final double acceleration;
    protected final double initialSpeed;
    protected final double targetSpeed;
    
    // 预计算的常数
    private final double t1; // 达到最大加速度所需的时间
    private final double halfJerk; // 0.5 * jerk
    private final double oneSixthJerk; // (1/6) * jerk
    private final double v1; // 第一阶段结束时的速度
    private final double s1; // 第一阶段结束时的距离
    private final boolean useSimpleCurve; // 是否使用简化的两阶段曲线
    private final double t3; // 总时间
    private final double t2Start; // 第三阶段开始时间（如果是完整曲线）
    private final double v2; // 第三阶段开始时的速度（如果是完整曲线）
    private final double s2; // 第三阶段开始时的距离（如果是完整曲线）
    private final double s3; // 曲线结束时的距离

    public AccelerateCommand(double jerk, double acceleration, double initialSpeed, double targetSpeed) {
        this.jerk = jerk;
        this.acceleration = acceleration;
        this.initialSpeed = initialSpeed;
        this.targetSpeed = targetSpeed;
        
        // 预计算常数
        this.t1 = acceleration / jerk;
        // 速度差
        double speedDiff = targetSpeed - initialSpeed;
        this.halfJerk = 0.5 * jerk;
        this.oneSixthJerk = (1.0 / 6.0) * jerk;
        this.v1 = initialSpeed + 0.5 * acceleration * t1;
        this.s1 = initialSpeed * t1 + oneSixthJerk * t1 * t1 * t1;
        // 判断是否需要完整 S 型曲线的阈值
        double threshold = 0.5 * acceleration * t1;
        
        // 判断是否需要完整的 S 型曲线
        this.useSimpleCurve = Math.abs(speedDiff) < threshold;
        
        if (useSimpleCurve) {
            // 简化曲线：两阶段，总时间为 2*t1
            this.t3 = 2 * t1;
            this.t2Start = 0; // 不使用
            this.v2 = 0; // 不使用
            this.s2 = 0; // 不使用
            // 计算简化曲线结束时的距离
            double t2Duration = t3 - t1;
            this.s3 = s1 + v1 * t2Duration + 0.5 * acceleration * t2Duration * t2Duration - oneSixthJerk * t2Duration * t2Duration * t2Duration;
        } else {
            // 完整曲线：三阶段
            // 计算实际需要的总时间
            // 第一阶段速度增加：0.5 * acceleration * t1
            // 第三阶段速度增加：0.5 * acceleration * t1
            // 第二阶段速度增加：speedDiff - acceleration * t1
            // 第二阶段时间：(speedDiff - acceleration * t1) / acceleration
            double t2Duration = (Math.abs(speedDiff) - acceleration * t1) / acceleration;
            if (t2Duration < 0) {
                // 如果速度差太小，实际上还是用简化曲线
                this.t3 = 2 * t1;
                this.t2Start = t1;
                double t2DurationValue = t3 - t1;
                this.s3 = s1 + v1 * t2DurationValue + 0.5 * acceleration * t2DurationValue * t2DurationValue - oneSixthJerk * t2DurationValue * t2DurationValue * t2DurationValue;
                this.v2 = 0; // 不使用
                this.s2 = 0; // 不使用
            } else {
                this.t2Start = t1 + t2Duration;
                this.t3 = t2Start + t1;
                
                // 计算第三阶段开始时的速度和距离
                double t2DurationValue = t2Start - t1;
                this.v2 = v1 + acceleration * t2DurationValue;
                this.s2 = s1 + v1 * t2DurationValue + 0.5 * acceleration * t2DurationValue * t2DurationValue;
                // 计算完整曲线结束时的距离
                this.s3 = s2 + v2 * t1 + 0.5 * acceleration * t1 * t1 - oneSixthJerk * t1 * t1 * t1;
            }
        }
    }

    protected double getSpeed(double t) {
        if (t <= 0) {
            return initialSpeed;
        }
        
        if (useSimpleCurve) {
            // 简化的两阶段加速过程
            if (t < t1) {
                // 第一阶段：加速度线性增加
                return initialSpeed + halfJerk * t * t;
            } else if (t < t3) {
                // 第二阶段：加速度线性减少
                double t2 = t - t1;
                return v1 + acceleration * t2 - halfJerk * t2 * t2;
            } else {
                // 已达到目标速度
                return targetSpeed;
            }
        } else {
            // 完整的三阶段 S 型曲线
            if (t < t1) {
                // 第一阶段：加速度从 0 线性增加到最大值
                return initialSpeed + halfJerk * t * t;
            } else if (t < t2Start) {
                // 第二阶段：加速度保持恒定
                return v1 + acceleration * (t - t1);
            } else if (t < t3) {
                // 第三阶段：加速度从最大值线性减少到 0
                double t2 = t - t2Start;
                return v2 + acceleration * t2 - halfJerk * t2 * t2;
            } else {
                // 已达到目标速度
                return targetSpeed;
            }
        }
    }

    protected double getDistance(double t) {
        if (t <= 0) {
            return 0;
        }

        double v = initialSpeed * t + oneSixthJerk * t * t * t;
        if (useSimpleCurve) {
            // 简化的两阶段加速过程
            if (t < t1) {
                // 第一阶段：加速度线性增加
                return v;
            } else if (t < t3) {
                // 第二阶段：加速度线性减少
                double t2 = t - t1;
                return s1 + v1 * t2 + 0.5 * acceleration * t2 * t2 - oneSixthJerk * t2 * t2 * t2;
            } else {
                // 已达到目标速度，匀速运动
                return s3 + targetSpeed * (t - t3);
            }
        } else {
            // 完整的三阶段 S 型曲线
            if (t < t1) {
                // 第一阶段：加速度从 0 线性增加到最大值
                return v;
            } else if (t < t2Start) {
                // 第二阶段：加速度保持恒定
                double dt = t - t1;
                return s1 + v1 * dt + 0.5 * acceleration * dt * dt;
            } else if (t < t3) {
                // 第三阶段：加速度从最大值线性减少到 0
                double t2 = t - t2Start;
                return s2 + v2 * t2 + 0.5 * acceleration * t2 * t2 - oneSixthJerk * t2 * t2 * t2;
            } else {
                // 已达到目标速度，匀速运动
                return s3 + targetSpeed * (t - t3);
            }
        }
    }

    @Override
    public double getSpeed() {
        return getSpeed(getTick());
    }

    @Override
    public double getDeltaS() {
        return getDistance(getTick()) - getDistance(getTick() - getDeltaT());
    }

    @Override
    public boolean isFinished() {
        return getTick() >= t3;
    }
}
