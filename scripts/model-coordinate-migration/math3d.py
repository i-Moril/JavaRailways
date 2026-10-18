"""Quaternion helpers; XYZ means Rx(x) * Ry(y) * Rz(z), angles in degrees."""
import math


def mul(a, b):
    x,y,z,w=a; X,Y,Z,W=b
    return (w*X+x*W+y*Z-z*Y, w*Y-x*Z+y*W+z*X,
            w*Z+x*Y-y*X+z*W, w*W-x*X-y*Y-z*Z)


def inverse(q):
    n=sum(v*v for v in q)
    if not math.isfinite(n) or n < 1e-24:
        raise ValueError('Invalid quaternion')
    return (-q[0]/n,-q[1]/n,-q[2]/n,q[3]/n)


def euler(v):
    if not all(math.isfinite(a) for a in v): raise ValueError('Non-finite Euler angle')
    q=(0.,0.,0.,1.)
    for i,angle in enumerate(v):
        h=math.radians(angle)/2; a=[0.,0.,0.,math.cos(h)]; a[i]=math.sin(h)
        q=mul(q,a)
    return q


def rotate(q,v):
    return mul(mul(q,(*v,0)),inverse(q))[:3]


def angles(q):
    cols=[rotate(q,v) for v in ((1,0,0),(0,1,0),(0,0,1))]
    m=[[cols[j][i] for j in range(3)] for i in range(3)]
    y=math.asin(max(-1.,min(1.,m[0][2])))
    if abs(math.cos(y)) > 1e-7:
        x=math.atan2(-m[1][2],m[2][2]); z=math.atan2(-m[0][1],m[0][0])
    else:
        x=math.atan2(m[2][1],m[1][1]); z=0.
    out=tuple(math.degrees(a) for a in (x,y,z))
    check=euler(out)
    if max(abs(a-b) for a,b in zip(rotate(q,(.37,.61,.83)),rotate(check,(.37,.61,.83)))) > 1e-6:
        raise ValueError('Euler recomposition failed')
    return out

C=euler((0,-90,0))
CI=inverse(C)


def vector(v):
    x,y,z=v
    return (-z,y,x)
