package work.art1st.mineRails.core.util;

import work.art1st.mineRails.core.api.math.Vector3D;

import java.util.ArrayList;
import java.util.List;

public class Octree {
    private static final int CAPACITY = 4; // 每个节点最多存储的点数
    private final Boundary boundary;
    private final List<Vector3D> points = new ArrayList<>();
    private Octree[] children;
    private boolean divided = false;

    public Octree(Vector3D p1, Vector3D p2) {
        this.boundary = new Boundary(
                Math.min(p1.getX(), p2.getX()), Math.max(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()), Math.max(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()), Math.max(p1.getZ(), p2.getZ())
        );
    }

    private Octree(Boundary boundary) {
        this.boundary = boundary;
    }

    // 分割节点
    private void subdivide() {
        double midX = (boundary.xMin + boundary.xMax) / 2;
        double midY = (boundary.yMin + boundary.yMax) / 2;
        double midZ = (boundary.zMin + boundary.zMax) / 2;

        children = new Octree[8];
        children[0] = new Octree(new Boundary(boundary.xMin, midX, boundary.yMin, midY, boundary.zMin, midZ));
        children[1] = new Octree(new Boundary(midX, boundary.xMax, boundary.yMin, midY, boundary.zMin, midZ));
        children[2] = new Octree(new Boundary(boundary.xMin, midX, midY, boundary.yMax, boundary.zMin, midZ));
        children[3] = new Octree(new Boundary(midX, boundary.xMax, midY, boundary.yMax, boundary.zMin, midZ));
        children[4] = new Octree(new Boundary(boundary.xMin, midX, boundary.yMin, midY, midZ, boundary.zMax));
        children[5] = new Octree(new Boundary(midX, boundary.xMax, boundary.yMin, midY, midZ, boundary.zMax));
        children[6] = new Octree(new Boundary(boundary.xMin, midX, midY, boundary.yMax, midZ, boundary.zMax));
        children[7] = new Octree(new Boundary(midX, boundary.xMax, midY, boundary.yMax, midZ, boundary.zMax));

        divided = true;
    }

    public void insert(Vector3D p) {
        if (!boundary.contains(p)) return;

        if (points.size() < CAPACITY && !divided) {
            points.add(p);
        } else {
            if (!divided) subdivide();
            for (Octree child : children) child.insert(p);
        }
    }

    // 核心功能：半径搜索 (Radius Search)
    public void getPointsWithinRadius(Vector3D center, double radius, List<Vector3D> found) {
        // 1. 如果当前节点的边界与球体不相交，直接返回
        if (!boundary.intersectsSphere(center, radius)) {
            return;
        }

        // 2. 检查当前节点中的点
        double r_r = radius * radius;
        for (Vector3D p : points) {
            double distSq = p.distanceSquared(center);
            if (distSq <= r_r) {
                found.add(p);
            }
        }

        // 3. 递归查询子节点
        if (divided) {
            for (Octree child : children) {
                child.getPointsWithinRadius(center, radius, found);
            }
        }
    }

    // 定义立方体边界
    private static class Boundary {
        double xMin, xMax, yMin, yMax, zMin, zMax;

        public Boundary(double xMin, double xMax, double yMin, double yMax, double zMin, double zMax) {
            this.xMin = xMin; this.xMax = xMax;
            this.yMin = yMin; this.yMax = yMax;
            this.zMin = zMin; this.zMax = zMax;
        }

        // 检查点是否在边界内
        public boolean contains(Vector3D p) {
            return (p.getX() >= xMin && p.getX() < xMax &&
                    p.getY() >= yMin && p.getY() < yMax &&
                    p.getZ() >= zMin && p.getZ() < zMax);
        }

        // 检查球体（查询范围）是否与此立方体边界相交
        public boolean intersectsSphere(Vector3D center, double radius) {
            double closestX = Math.max(xMin, Math.min(center.getX(), xMax));
            double closestY = Math.max(yMin, Math.min(center.getY(), yMax));
            double closestZ = Math.max(zMin, Math.min(center.getZ(), zMax));
            Vector3D closest = new Vector3D(closestX, closestY, closestZ);

            double distanceSq = center.distanceSquared(closest);
            return distanceSq <= (radius * radius);
        }
    }
}