/*
FIR Filter

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

public final class Filter {
	public static double sinc(double x) {
		if (x == 0)
			return 1;
		x *= Math.PI;
		return Math.sin(x) / x;
	}

	public static double lowPass(double cutoff, double rate, int n, int N) {
		double f = 2 * cutoff / rate;
		double x = n - (N - 1) / 2.0;
		return f * sinc(f * x);
	}
}
