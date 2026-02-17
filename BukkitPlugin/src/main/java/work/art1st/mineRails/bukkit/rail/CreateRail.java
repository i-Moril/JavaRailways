package work.art1st.mineRails.bukkit.rail;

import java.util.ArrayList;
import java.util.List;

public class CreateRail {

    public static class Vector3 {
        double x, y, z;
        public Vector3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public double distance(Vector3 v) {
            return Math.sqrt(Math.pow(x - v.x, 2) + Math.pow(y - v.y, 2) + Math.pow(z - v.z, 2));
        }
    }

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
    public static List<Vector3> generateRailway(Vector3 pStart, Vector3 pEnd, double R, double L, double step) {
        List<Vector3> points = new ArrayList<>();
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

            // 计算 Z 轴线性插值（坡度）
            tz = pStart.z + (pEnd.z - pStart.z) * (currentS / L_total);

            if (currentS < L) {
                // 第一段：第一条缓和曲线
                double[] coords = calculateClothoid(currentS, A);
                tx = coords[0]; ty = coords[1];
            } else if (currentS <= L + L_arc) {
                // 第二段：圆曲线 (简化为圆弧公式)
                double sInArc = currentS - L;
                double angle = sInArc / R;
                // 对接缓和曲线终点的切向
                tx = L + R * Math.sin(angle);
                ty = (Math.pow(L, 3)/(6*A*A)) + R * (1 - Math.cos(angle));
            } else {
                // 第三段：第二条缓和曲线 (镜像逻辑)
                double sInLastTransition = L_total - currentS;
                double[] coords = calculateClothoid(sInLastTransition, A);
                tx = L_total - coords[0];
                ty = coords[1];
            }

            // 此处 tx, ty 是在局部坐标系下的，实际应用需通过旋转矩阵转换到 pStart -> pEnd 的切向量方向
            // 为简化演示，直接输出坐标
            points.add(new Vector3(tx + pStart.x, ty + pStart.y, tz));
            currentS += step;
        }

        return points;
    }

    public static void main(String[] args) {
        Vector3 start = new Vector3(0, 0, 0);
        Vector3 end = new Vector3(500, 100, 10);
        double R = 400; // 最小半径
        double L = 50;  // 缓和曲线长度

        List<Vector3> track = generateRailway(start, end, R, L, 2.0);

        System.out.println("离散点生成完毕，总计: " + track.size() + " 点");
        for (int i = 0; i < Math.min(10, track.size()); i++) {
            Vector3 v = track.get(i);
            System.out.printf("Point %d: [%.2f, %.2f, %.2f]\n", i, v.x, v.y, v.z);
        }
    }
}