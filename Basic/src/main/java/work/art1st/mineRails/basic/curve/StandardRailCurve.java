package work.art1st.mineRails.basic.curve;

import work.art1st.mineRails.core.util.Vector3D;

import java.util.ArrayList;
import java.util.List;

public class StandardRailCurve {
    /**
     * 简化的回旋线（缓和曲线）坐标计算 - 使用级数展开近似 Fresnel 积分
     * @param l 当前长度
     * @param A 回旋线参数 (A = sqrt(R * L_total))
     * @return 相对坐标
     */
    public static double[] calculateClothoid(double l, double A) {
        double x = l - Math.pow(l, 5) / (40 * Math.pow(A, 4));
        double y = Math.pow(l, 3) / (6 * Math.pow(A, 2)) - Math.pow(l, 7) / (336 * Math.pow(A, 6));
        return new double[]{x, y};
    }

    /**
     * 生成三维轨道点列表
     * @param pStart 起点
     * @param pEnd 终点
     * @param R 圆曲线半径
     * @param L 缓和曲线长度
     * @param step 步长（米）
     */
    public static List<Vector3D> generateRailway(Vector3D pStart, Vector3D pEnd, double R, double L, double step) {
        List<Vector3D> points = new ArrayList<>();
        double totalDist = pStart.distance(pEnd);

        // 1. 简化的几何划分：假设轨道由两端缓和曲线和中间圆曲线组成
        // 实际工程中需根据转角计算圆曲线长度，此处为演示离散化逻辑
        double L_total = totalDist;
        double L_arc = L_total - 2 * L; // 圆曲线长度

        if (L_arc < 0) {
            System.out.println("警告：距离太短，无法容纳给定的缓和曲线长度！");
            return points;
        }

        double A = Math.sqrt(R * L); // 回旋线参数
        double currentS = 0;

        while (currentS <= L_total) {
            double tx, ty, tz;

            // 计算 Y 轴线性插值（坡度）
            ty = pStart.getY() + (pEnd.getY() - pStart.getY()) * (currentS / L_total);

            if (currentS < L) {
                // 第一段：第一条缓和曲线
                double[] coords = calculateClothoid(currentS, A);
                tx = coords[0]; tz = coords[2];
            } else if (currentS <= L + L_arc) {
                // 第二段：圆曲线 (简化为圆弧公式)
                double sInArc = currentS - L;
                double angle = sInArc / R;
                // 对接缓和曲线终点的切向
                tx = L + R * Math.sin(angle);
                tz = (Math.pow(L, 3)/(6*A*A)) + R * (1 - Math.cos(angle));
            } else {
                // 第三段：第二条缓和曲线 (镜像逻辑)
                double sInLastTransition = L_total - currentS;
                double[] coords = calculateClothoid(sInLastTransition, A);
                tx = L_total - coords[0];
                tz = coords[2];
            }

            // 此处 tx, tz 是在局部坐标系下的，实际应用需通过旋转矩阵转换到 pStart -> pEnd 的切向量方向
            // 为简化演示，直接输出坐标
            points.add(new Vector3D(tx + pStart.getX(), ty, tz + pStart.getZ()));
            currentS += step;
        }

        return points;
    }
}
