package io.github.prefabdeploy.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GridTransformTest {
  @Test
  void cellsAndContinuousCoordinatesAgreeAtAllFourRotations() {
    for (int turns = 0; turns < 4; turns++) {
      var t = new GridTransform(-117, 63, 201, turns, 4);
      for (int x = 0; x < 48; x++)
        for (int z = 0; z < 7; z++) {
          var cell = t.cell(x, 1, z);
          var center = t.point(x + .5, 1.5, z + .5);
          assertEquals(cell.x(), (int) Math.floor(center.x()));
          assertEquals(cell.y(), (int) Math.floor(center.y()));
          assertEquals(cell.z(), (int) Math.floor(center.z()));
        }
    }
  }

  @Test
  void fortyEightWideMeansBoundaryDistanceFortyEight() {
    for (int turns = 0; turns < 4; turns++) {
      var t = new GridTransform(100, 50, 200, turns, 0);
      var b = t.cornerB(48);
      assertEquals(48, Math.abs(b.x() - 100) + Math.abs(b.z() - 200));
      assertEquals(turns, GridTransform.direction(b.x() - 100, b.z() - 200, 48));
      assertEquals(-1, GridTransform.direction(b.x() - 100, b.z() - 200, 47));
    }
  }

  @Test
  void basementUsesReferencePlaneRatherThanLowestBlock() {
    var t = new GridTransform(0, 70, 0, 0, 4);
    assertEquals(66, t.cell(0, 0, 0).y());
    assertEquals(70, t.cell(0, 4, 0).y());
    assertEquals(74, t.cell(0, 8, 0).y());
  }

  @Test
  void beaconCIsPerpendicularWithNoMirror() {
    var t = new GridTransform(10, 60, 20, 1, 3);
    assertEquals(new GridTransform.Cell(10, 60, 68), t.cornerB(48));
    assertEquals(new GridTransform.Cell(-6, 60, 20), t.cornerC(16));
  }
}
