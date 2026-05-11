package com.katka.engine

import kotlin.math.abs

/**
 * Low-level matrix arithmetic used internally by the Kalman filter.
 * All matrices are represented as Array<DoubleArray> (row-major).
 *
 * No external linear-algebra library is used so the module stays pure-Kotlin
 * and deployable to any target (JVM, Android, multiplatform).
 */
object MatrixOps {

    // ── Construction ────────────────────────────────────────────────────────

    fun zeros(rows: Int, cols: Int): Array<DoubleArray> =
        Array(rows) { DoubleArray(cols) }

    fun identity(n: Int): Array<DoubleArray> =
        Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

    fun diagonal(values: DoubleArray): Array<DoubleArray> {
        val n = values.size
        return Array(n) { i -> DoubleArray(n) { j -> if (i == j) values[i] else 0.0 } }
    }

    /** Deep-copy a matrix. */
    fun copy(A: Array<DoubleArray>): Array<DoubleArray> =
        Array(A.size) { i -> A[i].copyOf() }

    // ── Element-wise ops ────────────────────────────────────────────────────

    fun add(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == B.size && A[0].size == B[0].size) { "add: shape mismatch" }
        return Array(A.size) { i -> DoubleArray(A[0].size) { j -> A[i][j] + B[i][j] } }
    }

    fun sub(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == B.size && A[0].size == B[0].size) { "sub: shape mismatch" }
        return Array(A.size) { i -> DoubleArray(A[0].size) { j -> A[i][j] - B[i][j] } }
    }

    fun scale(A: Array<DoubleArray>, s: Double): Array<DoubleArray> =
        Array(A.size) { i -> DoubleArray(A[0].size) { j -> A[i][j] * s } }

    // ── Multiplication ───────────────────────────────────────────────────────

    /** Matrix × Matrix */
    fun mul(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size; val p = B[0].size; val k = B.size
        require(A[0].size == k) { "mul: inner dimensions don't match (${A[0].size} vs $k)" }
        return Array(n) { i ->
            DoubleArray(p) { j -> (0 until k).sumOf { l -> A[i][l] * B[l][j] } }
        }
    }

    /** Matrix × column vector */
    fun mulVec(A: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        require(A[0].size == v.size) { "mulVec: shape mismatch" }
        return DoubleArray(A.size) { i -> (v.indices).sumOf { j -> A[i][j] * v[j] } }
    }

    /** Outer product: column vector × row vector → matrix (n×m) */
    fun outerProduct(a: DoubleArray, b: DoubleArray): Array<DoubleArray> =
        Array(a.size) { i -> DoubleArray(b.size) { j -> a[i] * b[j] } }

    // ── Structural ops ───────────────────────────────────────────────────────

    fun transpose(A: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size; val m = A[0].size
        return Array(m) { i -> DoubleArray(n) { j -> A[j][i] } }
    }

    /**
     * In-place add a small epsilon to each diagonal entry to prevent numerical
     * singularity before inverting the innovation covariance S.
     */
    fun addDiagEps(A: Array<DoubleArray>, eps: Double = 1e-9): Array<DoubleArray> {
        val R = copy(A)
        for (i in R.indices) R[i][i] += eps
        return R
    }

    // ── Inversion ────────────────────────────────────────────────────────────

    /**
     * Gauss-Jordan elimination with partial pivoting.
     * Works well for the small matrices (2×2, 4×4) used in the Kalman filter.
     *
     * @throws IllegalStateException if the matrix is singular (det ≈ 0).
     */
    fun inverse(A: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size
        require(n > 0 && A.all { it.size == n }) { "inverse: must be square" }

        // Build augmented matrix [A | I]
        val aug = Array(n) { i ->
            DoubleArray(2 * n) { j -> if (j < n) A[i][j] else if (j - n == i) 1.0 else 0.0 }
        }

        for (col in 0 until n) {
            // Partial pivot: swap row with the largest absolute value in this column
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            val pivot = aug[col][col]
            check(abs(pivot) > 1e-14) { "inverse: matrix is singular (pivot ≈ 0 at col=$col)" }

            // Normalise pivot row
            for (j in 0 until 2 * n) aug[col][j] /= pivot

            // Eliminate column in all other rows
            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                for (j in 0 until 2 * n) aug[row][j] -= factor * aug[col][j]
            }
        }

        return Array(n) { i -> DoubleArray(n) { j -> aug[i][j + n] } }
    }

    // ── Symmetrisation (combat numerical drift in P) ─────────────────────────

    /** Force a matrix to be exactly symmetric: A = (A + Aᵀ) / 2 */
    fun symmetrise(A: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size
        return Array(n) { i -> DoubleArray(n) { j -> (A[i][j] + A[j][i]) / 2.0 } }
    }

    // ── Debug helpers ────────────────────────────────────────────────────────

    fun Array<DoubleArray>.toDebugString(decimals: Int = 4): String {
        val fmt = "%.${decimals}f"
        return joinToString(prefix = "[\n", postfix = "\n]", separator = "\n") { row ->
            "  " + row.joinToString(" ") { fmt.format(it) }
        }
    }

    fun DoubleArray.toDebugString(decimals: Int = 4): String {
        val fmt = "%.${decimals}f"
        return "[" + joinToString(", ") { fmt.format(it) } + "]"
    }
}