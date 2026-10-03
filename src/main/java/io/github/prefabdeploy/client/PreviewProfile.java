package io.github.prefabdeploy.client;

import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Optional asynchronous GPU profiling; disabled during ordinary play. */
final class PreviewProfile {
  private record Query(int begin, int end) {}

  private static final ArrayDeque<Query> PENDING = new ArrayDeque<>();
  private static final List<Long> SAMPLES = new ArrayList<>();

  static int begin() {
    if (!Boolean.getBoolean("prefabdeploy.clientSmoke") || !GL.getCapabilities().OpenGL33) return 0;
    poll();
    if (PENDING.size() > 16) return 0;
    int query = GL15.glGenQueries();
    GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
    return query;
  }

  static void end(int begin) {
    if (begin == 0) return;
    int end = GL15.glGenQueries();
    GL33.glQueryCounter(end, GL33.GL_TIMESTAMP);
    PENDING.addLast(new Query(begin, end));
  }

  static void poll() {
    while (!PENDING.isEmpty()
        && GL15.glGetQueryObjecti(PENDING.getFirst().end, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
      var q = PENDING.removeFirst();
      SAMPLES.add(
          GL33.glGetQueryObjectui64(q.end, GL15.GL_QUERY_RESULT)
              - GL33.glGetQueryObjectui64(q.begin, GL15.GL_QUERY_RESULT));
      GL15.glDeleteQueries(q.begin);
      GL15.glDeleteQueries(q.end);
    }
  }

  static void reset() {
    poll();
    for (var q : PENDING) {
      GL15.glDeleteQueries(q.begin);
      GL15.glDeleteQueries(q.end);
    }
    PENDING.clear();
    SAMPLES.clear();
  }

  static double p95() {
    poll();
    if (SAMPLES.isEmpty()) return -1;
    var a = SAMPLES.stream().mapToLong(Long::longValue).sorted().toArray();
    return a[Math.min(a.length - 1, (int) (a.length * .95))] / 1e6;
  }
}
