package b43;

import java.util.ArrayList;
import java.util.List;

public final class AsciiChart {
    public static final class Series {
        final String name;
        final char mark;
        final double[] values;

        public Series(String name, char mark, double[] values) {
            this.name = name;
            this.mark = mark;
            this.values = values;
        }
    }

    private static final int WIDTH = 72;
    private static final int HEIGHT = 18;

    private AsciiChart() {}

    public static void plot(String title, List<String> dates, List<Series> series) {
        int n = dates.size();
        if (n == 0) {
            System.out.println("  (no data to plot)");
            return;
        }
        int cols = Math.min(WIDTH, n);

        List<double[]> bucketed = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            double[] b = new double[cols];
            for (int c = 0; c < cols; c++) {
                int from = (int) ((long) c * n / cols);
                int to = (int) ((long) (c + 1) * n / cols);
                double sum = 0;
                int cnt = 0;
                for (int i = from; i < Math.max(to, from + 1) && i < n; i++) {
                    if (!Double.isNaN(s.values[i])) {
                        sum += s.values[i];
                        cnt++;
                    }
                }
                b[c] = cnt == 0 ? Double.NaN : sum / cnt;
                if (cnt > 0) {
                    min = Math.min(min, b[c]);
                    max = Math.max(max, b[c]);
                }
            }
            bucketed.add(b);
        }
        if (min == Double.POSITIVE_INFINITY) {
            System.out.println("  (no data to plot)");
            return;
        }
        if (max - min < 1e-9) {
            max += 1;
            min -= 1;
        }

        char[][] grid = new char[HEIGHT][cols];
        for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        for (int si = 0; si < series.size(); si++) {
            double[] b = bucketed.get(si);
            for (int c = 0; c < cols; c++) {
                if (Double.isNaN(b[c])) continue;
                int r = (int) Math.round((max - b[c]) / (max - min) * (HEIGHT - 1));
                grid[r][c] = series.get(si).mark;
            }
        }

        System.out.println();
        System.out.println("  " + title);
        for (int r = 0; r < HEIGHT; r++) {
            String label = "";
            if (r == 0 || r == HEIGHT - 1 || r == HEIGHT / 2) {
                double v = max - (max - min) * r / (HEIGHT - 1);
                label = String.format("%.2f", v);
            }
            System.out.printf("  %10s |%s%n", label, new String(grid[r]));
        }
        System.out.println("  " + Out.repeat(' ', 10) + " +" + Out.repeat('-', cols));
        String first = dates.get(0);
        String last = dates.get(n - 1);
        int gap = Math.max(1, cols - first.length() - last.length());
        System.out.println("  " + Out.repeat(' ', 12) + first + Out.repeat(' ', gap) + last);
        StringBuilder legend = new StringBuilder("  Legend: ");
        for (Series s : series) legend.append("'").append(s.mark).append("' ").append(s.name).append("   ");
        System.out.println(legend);
    }
}
