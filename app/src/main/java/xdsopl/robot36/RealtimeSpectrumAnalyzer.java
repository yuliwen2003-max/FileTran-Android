package xdsopl.robot36;

public class RealtimeSpectrumAnalyzer {
	private final ShortTimeFourierTransform stft;
	private final Complex input;
	private final float[] latest;

	public RealtimeSpectrumAnalyzer(int fftSize) {
		stft = new ShortTimeFourierTransform(fftSize, 3);
		input = new Complex();
		latest = new float[fftSize / 2];
	}

	public boolean pushPcm16(short[] pcm, int length) {
		boolean updated = false;
		int use = Math.max(0, Math.min(length, pcm.length));
		for (int i = 0; i < use; ++i) {
			input.set(pcm[i] / 32768.0f, 0f);
			if (stft.push(input)) {
				for (int k = 0; k < latest.length; ++k) {
					float p = stft.power[k];
					float db = (float)(10.0 * Math.log10(1e-12 + p));
					float normalized = (db + 70f) / 70f;
					latest[k] = Math.max(0f, Math.min(1f, normalized));
				}
				updated = true;
			}
		}
		return updated;
	}

	public float[] downsample(int bins) {
		int n = Math.max(1, bins);
		float[] out = new float[n];
		for (int i = 0; i < n; ++i) {
			int from = i * latest.length / n;
			int to = Math.max(from + 1, (i + 1) * latest.length / n);
			float sum = 0f;
			for (int j = from; j < to && j < latest.length; ++j) {
				sum += latest[j];
			}
			out[i] = sum / (to - from);
		}
		return out;
	}
}

