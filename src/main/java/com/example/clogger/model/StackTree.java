package com.example.clogger.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A tree of stack samples, like spark's: each node is a frame, `total` counts samples that were inside it. */
public final class StackTree {
    public static final class Node {
        public final String frame;
        public long total, self;
        public final Map<String, Node> children = new HashMap<>();
        Node(String frame) { this.frame = frame; }
    }

    public final Node root = new Node("(root)");
    private final int maxDepth;

    public StackTree(int maxDepth) { this.maxDepth = maxDepth; }

    public long samples() { return root.total; }

    /** Adds one sample. frames are listed ROOT FIRST (outermost call first). */
    public void add(List<String> frames) {
        Node n = root;
        n.total++;
        int depth = 0;
        for (String f : frames) {
            if (++depth > maxDepth) break;
            n = n.children.computeIfAbsent(f, Node::new);
            n.total++;
        }
        n.self++;
    }

    /**
     * Prints the tree as indented text. Nodes under minPercent of all samples are folded away. A long chain where every
     * frame has exactly one child (thread start-up, main loop) is shown as one line.
     */
    public String render(double minPercent, int maxLines, Function<String, String> pretty) {
        StringBuilder sb = new StringBuilder();
        if (root.total == 0) return "";
        int[] lines = {0};
        // skip the shared prefix
        Node n = root;
        int skipped = 0;
        while (n.children.size() == 1) {
            Node only = n.children.values().iterator().next();
            if (only.total < n.total * 0.98 && n != root) break;
            n = only;
            skipped++;
            if (skipped > 400) break;
        }
        if (skipped > 1) sb.append("  (").append(skipped).append(" frames all samples share, down to ").append(pretty.apply(n.frame)).append(")\n");
        else n = root;
        print(sb, n, 0, root.total, minPercent, maxLines, lines, pretty);
        if (lines[0] >= maxLines) sb.append("  ... (cut: too many lines)\n");
        return sb.toString();
    }

    private void print(StringBuilder sb, Node n, int depth, long all, double minPercent, int maxLines, int[] lines, Function<String, String> pretty) {
        if (lines[0] >= maxLines) return;
        double pct = n.total * 100.0 / all;
        sb.append("  ").append("  ".repeat(Math.min(depth, 40))).append(String.format("%5.1f%%  ", pct)).append(pretty.apply(n.frame));
        if (n.self > 0 && !n.children.isEmpty()) sb.append(String.format("   (itself %.1f%%)", n.self * 100.0 / all));
        sb.append('\n');
        lines[0]++;
        List<Node> kids = new ArrayList<>(n.children.values());
        kids.sort(Comparator.comparingLong((Node k) -> k.total).reversed());
        long hidden = 0;
        for (Node k : kids) {
            if (k.total * 100.0 / all < minPercent) { hidden += k.total; continue; }
            print(sb, k, depth + 1, all, minPercent, maxLines, lines, pretty);
        }
        if (hidden > 0 && hidden * 100.0 / all >= minPercent) {
            sb.append("  ").append("  ".repeat(Math.min(depth + 1, 40))).append(String.format("%5.1f%%  (many small ones)\n", hidden * 100.0 / all));
            lines[0]++;
        }
    }
}
