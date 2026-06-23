package com.example.myapplication.board3d.math

import kotlin.math.*

class Vector3f {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0f

    constructor()
    constructor(x: Float, y: Float, z: Float) {
        this.x = x
        this.y = y
        this.z = z
    }
    constructor(v: Vector3f) {
        this.x = v.x
        this.y = v.y
        this.z = v.z
    }

    fun set(x: Float, y: Float, z: Float): Vector3f {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun normalize(): Vector3f {
        val len = sqrt(x * x + y * y + z * z)
        if (len > 0f) { x /= len; y /= len; z /= len }
        return this
    }

    fun add(v: Vector3f): Vector3f {
        this.x += v.x; this.y += v.y; this.z += v.z
        return this
    }

    fun mul(s: Float): Vector3f {
        this.x *= s; this.y *= s; this.z *= s
        return this
    }
    
    fun dot(v: Vector3f): Float = x * v.x + y * v.y + z * v.z
    
    fun cross(v: Vector3f): Vector3f {
        val rx = y * v.z - z * v.y
        val ry = z * v.x - x * v.z
        val rz = x * v.y - y * v.x
        this.x = rx; this.y = ry; this.z = rz
        return this
    }
}

class Matrix4f {
    val m = FloatArray(16) { if (it % 5 == 0) 1f else 0f } // identity

    constructor()
    constructor(other: Matrix4f) {
        other.m.copyInto(this.m)
    }

    fun translate(x: Float, y: Float, z: Float): Matrix4f {
        m[12] = m[0] * x + m[4] * y + m[8] * z + m[12]
        m[13] = m[1] * x + m[5] * y + m[9] * z + m[13]
        m[14] = m[2] * x + m[6] * y + m[10] * z + m[14]
        m[15] = m[3] * x + m[7] * y + m[11] * z + m[15]
        return this
    }

    fun rotateY(angleRad: Float): Matrix4f {
        val s = sin(angleRad)
        val c = cos(angleRad)
        val nm00 = m[0] * c + m[8] * -s
        val nm01 = m[1] * c + m[9] * -s
        val nm02 = m[2] * c + m[10] * -s
        val nm03 = m[3] * c + m[11] * -s
        m[8] = m[0] * s + m[8] * c
        m[9] = m[1] * s + m[9] * c
        m[10] = m[2] * s + m[10] * c
        m[11] = m[3] * s + m[11] * c
        m[0] = nm00
        m[1] = nm01
        m[2] = nm02
        m[3] = nm03
        return this
    }

    fun rotate(qx: Float, qy: Float, qz: Float, qw: Float): Matrix4f {
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        val rm = Matrix4f()
        rm.m[0] = 1f - 2f * (yy + zz); rm.m[4] = 2f * (xy - wz); rm.m[8] = 2f * (xz + wy)
        rm.m[1] = 2f * (xy + wz); rm.m[5] = 1f - 2f * (xx + zz); rm.m[9] = 2f * (yz - wx)
        rm.m[2] = 2f * (xz - wy); rm.m[6] = 2f * (yz + wx); rm.m[10] = 1f - 2f * (xx + yy)
        return this.mul(rm)
    }

    fun scale(sx: Float, sy: Float, sz: Float): Matrix4f {
        m[0] *= sx; m[1] *= sx; m[2] *= sx; m[3] *= sx
        m[4] *= sy; m[5] *= sy; m[6] *= sy; m[7] *= sy
        m[8] *= sz; m[9] *= sz; m[10] *= sz; m[11] *= sz
        return this
    }

    fun invert(): Matrix4f {
        val a = m[0] * m[5] - m[1] * m[4]
        val b = m[0] * m[6] - m[2] * m[4]
        val c = m[0] * m[7] - m[3] * m[4]
        val d = m[1] * m[6] - m[2] * m[5]
        val e = m[1] * m[7] - m[3] * m[5]
        val f = m[2] * m[7] - m[3] * m[6]
        val g = m[8] * m[13] - m[9] * m[12]
        val h = m[8] * m[14] - m[10] * m[12]
        val i = m[8] * m[15] - m[11] * m[12]
        val j = m[9] * m[14] - m[10] * m[13]
        val k = m[9] * m[15] - m[11] * m[13]
        val l = m[10] * m[15] - m[11] * m[14]
        val det = a * l - b * k + c * j + d * i - e * h + f * g
        if (det == 0f) return this
        val invDet = 1.0f / det
        val nm00 = ( m[5] * l - m[6] * k + m[7] * j) * invDet
        val nm01 = (-m[1] * l + m[2] * k - m[3] * j) * invDet
        val nm02 = ( m[13] * f - m[14] * e + m[15] * d) * invDet
        val nm03 = (-m[9] * f + m[10] * e - m[11] * d) * invDet
        val nm10 = (-m[4] * l + m[6] * i - m[7] * h) * invDet
        val nm11 = ( m[0] * l - m[2] * i + m[3] * h) * invDet
        val nm12 = (-m[12] * f + m[14] * c - m[15] * b) * invDet
        val nm13 = ( m[8] * f - m[10] * c + m[11] * b) * invDet
        val nm20 = ( m[4] * k - m[5] * i + m[7] * g) * invDet
        val nm21 = (-m[0] * k + m[1] * i - m[3] * g) * invDet
        val nm22 = ( m[12] * e - m[13] * c + m[15] * a) * invDet
        val nm23 = (-m[8] * e + m[9] * c - m[11] * a) * invDet
        val nm30 = (-m[4] * j + m[5] * h - m[6] * g) * invDet
        val nm31 = ( m[0] * j - m[1] * h + m[2] * g) * invDet
        val nm32 = (-m[12] * d + m[13] * b - m[14] * a) * invDet
        val nm33 = ( m[8] * d - m[9] * b + m[10] * a) * invDet
        m[0] = nm00; m[1] = nm01; m[2] = nm02; m[3] = nm03
        m[4] = nm10; m[5] = nm11; m[6] = nm12; m[7] = nm13
        m[8] = nm20; m[9] = nm21; m[10] = nm22; m[11] = nm23
        m[12] = nm30; m[13] = nm31; m[14] = nm32; m[15] = nm33
        return this
    }

    fun transpose(): Matrix4f {
        val m01 = m[1]; val m02 = m[2]; val m03 = m[3]
        val m12 = m[6]; val m13 = m[7]
        val m23 = m[11]
        m[1] = m[4]; m[2] = m[8]; m[3] = m[12]
        m[4] = m01; m[6] = m[9]; m[7] = m[13]
        m[8] = m02; m[9] = m12; m[11] = m[14]
        m[12] = m03; m[13] = m13; m[14] = m23
        return this
    }

    fun transformPosition(v: Vector3f): Vector3f {
        val x = v.x; val y = v.y; val z = v.z
        v.x = m[0] * x + m[4] * y + m[8] * z + m[12]
        v.y = m[1] * x + m[5] * y + m[9] * z + m[13]
        v.z = m[2] * x + m[6] * y + m[10] * z + m[14]
        return v
    }

    fun transformDirection(v: Vector3f): Vector3f {
        val x = v.x; val y = v.y; val z = v.z
        v.x = m[0] * x + m[4] * y + m[8] * z
        v.y = m[1] * x + m[5] * y + m[9] * z
        v.z = m[2] * x + m[6] * y + m[10] * z
        return v
    }

    fun perspective(fovy: Float, aspect: Float, zNear: Float, zFar: Float, zZeroToOne: Boolean): Matrix4f {
        val h = tan(fovy * 0.5f)
        m[0] = 1.0f / (h * aspect)
        m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = 0f
        m[5] = 1.0f / h
        m[6] = 0f; m[7] = 0f
        m[8] = 0f; m[9] = 0f
        m[11] = -1.0f
        m[12] = 0f; m[13] = 0f; m[15] = 0f
        if (zZeroToOne) {
            m[10] = zFar / (zNear - zFar)
            m[14] = -(zFar * zNear) / (zFar - zNear)
        } else {
            m[10] = (zFar + zNear) / (zNear - zFar)
            m[14] = (2f * zFar * zNear) / (zNear - zFar)
        }
        return this
    }

    fun ortho(left: Float, right: Float, bottom: Float, top: Float, zNear: Float, zFar: Float, zZeroToOne: Boolean): Matrix4f {
        m[0] = 2.0f / (right - left)
        m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = 0f
        m[5] = 2.0f / (top - bottom)
        m[6] = 0f; m[7] = 0f
        m[8] = 0f; m[9] = 0f; m[11] = 0f
        m[12] = -(right + left) / (right - left)
        m[13] = -(top + bottom) / (top - bottom)
        m[15] = 1.0f
        if (zZeroToOne) {
            m[10] = -1.0f / (zFar - zNear)
            m[14] = -zNear / (zFar - zNear)
        } else {
            m[10] = -2.0f / (zFar - zNear)
            m[14] = -(zFar + zNear) / (zFar - zNear)
        }
        return this
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float): Matrix4f {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val flen = sqrt(fx*fx + fy*fy + fz*fz)
        if (flen > 0f) { fx /= flen; fy /= flen; fz /= flen }
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        val slen = sqrt(sx*sx + sy*sy + sz*sz)
        if (slen > 0f) { sx /= slen; sy /= slen; sz /= slen }
        var ux2 = sy * fz - sz * fy
        var uy2 = sz * fx - sx * fz
        var uz2 = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * ex + sy * ey + sz * ez)
        m[1] = ux2; m[5] = uy2; m[9] = uz2; m[13] = -(ux2 * ex + uy2 * ey + uz2 * ez)
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = -(-fx * ex - fy * ey - fz * ez)
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1.0f
        return this
    }
    
    fun lookAt(eye: Vector3f, center: Vector3f, up: Vector3f): Matrix4f {
        return lookAt(eye.x, eye.y, eye.z, center.x, center.y, center.z, up.x, up.y, up.z)
    }

    fun mul(other: Matrix4f): Matrix4f {
        val m00 = m[0]; val m01 = m[1]; val m02 = m[2]; val m03 = m[3]
        val m10 = m[4]; val m11 = m[5]; val m12 = m[6]; val m13 = m[7]
        val m20 = m[8]; val m21 = m[9]; val m22 = m[10]; val m23 = m[11]
        val m30 = m[12]; val m31 = m[13]; val m32 = m[14]; val m33 = m[15]
        
        val o00 = other.m[0]; val o01 = other.m[1]; val o02 = other.m[2]; val o03 = other.m[3]
        val o10 = other.m[4]; val o11 = other.m[5]; val o12 = other.m[6]; val o13 = other.m[7]
        val o20 = other.m[8]; val o21 = other.m[9]; val o22 = other.m[10]; val o23 = other.m[11]
        val o30 = other.m[12]; val o31 = other.m[13]; val o32 = other.m[14]; val o33 = other.m[15]

        m[0] = m00 * o00 + m10 * o01 + m20 * o02 + m30 * o03
        m[1] = m01 * o00 + m11 * o01 + m21 * o02 + m31 * o03
        m[2] = m02 * o00 + m12 * o01 + m22 * o02 + m32 * o03
        m[3] = m03 * o00 + m13 * o01 + m23 * o02 + m33 * o03
        m[4] = m00 * o10 + m10 * o11 + m20 * o12 + m30 * o13
        m[5] = m01 * o10 + m11 * o11 + m21 * o12 + m31 * o13
        m[6] = m02 * o10 + m12 * o11 + m22 * o12 + m32 * o13
        m[7] = m03 * o10 + m13 * o11 + m23 * o12 + m33 * o13
        m[8] = m00 * o20 + m10 * o21 + m20 * o22 + m30 * o23
        m[9] = m01 * o20 + m11 * o21 + m21 * o22 + m31 * o23
        m[10] = m02 * o20 + m12 * o21 + m22 * o22 + m32 * o23
        m[11] = m03 * o20 + m13 * o21 + m23 * o22 + m33 * o23
        m[12] = m00 * o30 + m10 * o31 + m20 * o32 + m30 * o33
        m[13] = m01 * o30 + m11 * o31 + m21 * o32 + m31 * o33
        m[14] = m02 * o30 + m12 * o31 + m22 * o32 + m32 * o33
        m[15] = m03 * o30 + m13 * o31 + m23 * o32 + m33 * o33
        return this
    }

    fun get(dest: FloatArray, offset: Int = 0) {
        m.copyInto(dest, offset)
    }
}
