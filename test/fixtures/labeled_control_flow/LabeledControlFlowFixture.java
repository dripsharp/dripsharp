public final class LabeledControlFlowFixture {
  public static int labeledBlocksAndIf() {
    int result = 1;
    block: {
      result += 2;
      if (result == 3) break block;
      result += 100;
    }
    single: if (result == 3) {
      result += 4;
      break single;
    } else {
      result += 100;
    }
    return result;
  }

  public static int nestedLoops() {
    int result = 0;
    outer: for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        if (j == 1) continue outer;
        result += i * 10 + j;
      }
    }
    return result;
  }

  public static int everyLoopKind() {
    int result = 0;
    each: for (int value : new int[] {1, 2, 3}) {
      if (value == 2) continue each;
      result += value;
    }
    int i = 0;
    repeat: while (i < 3) {
      i++;
      if (i == 1) continue repeat;
      result += i;
    }
    int j = 0;
    again: do {
      j++;
      if (j < 2) continue again;
      result += j;
    } while (j < 2);
    return result;
  }

  public static int singleStatementLoop() {
    int result = 0;
    outer: for (int i = 0; i < 4; i++)
      if (i < 2) {
        result++;
        continue outer;
      } else {
        break outer;
      }
    return result;
  }

  public static int disjointLabelReuse() {
    int result = 0;
    same: {
      result++;
      break same;
    }
    same: {
      result += 2;
      break same;
    }
    return result;
  }

  public static int nestedDistinctLabels() {
    int result = 0;
    outer: {
      inner: {
        result = 7;
        break outer;
      }
    }
    return result;
  }

  public static int tryFinally() {
    int result = 0;
    outer: for (int i = 0; i < 3; i++) {
      try {
        result++;
        if (i == 0) continue outer;
        break outer;
      } finally {
        result += 10;
      }
    }
    section: try {
      result += 3;
      break section;
    } finally {
      result += 100;
    }
    return result;
  }

  public static int branchFromFinally() {
    int result = 0;
    outer: while (result < 3) {
      try {
        result++;
      } finally {
        if (result == 2) break outer;
      }
    }
    overrideReturn: {
      try {
        if (result == 2) return 99;
      } finally {
        if (result == 2) {
          result += 3;
          break overrideReturn;
        }
      }
    }
    caught: while (true) {
      try {
        result += 0;
      } finally {
        try {
          break caught;
        } catch (Exception ignored) {
          result += 1000;
        }
      }
    }
    nested: while (true) {
      try {
        result += 0;
      } finally {
        try {
          result += 0;
        } finally {
          if (result == 5) break nested;
        }
      }
    }
    int continued = 0;
    continueFromFinally: for (int i = 0; i < 3; i++) {
      try {
        continued += 10;
      } finally {
        if (i < 2) {
          continued++;
          continue continueFromFinally;
        }
      }
      continued += 100;
    }
    result += continued;
    return result;
  }

  public static int switchInteraction() {
    int result = 0;
    outer: for (int i = 0; i < 3; i++) {
      switch (i) {
        case 0:
          result++;
          continue outer;
        case 1:
          result += 10;
          break outer;
        default:
          result += 100;
      }
    }
    selected: switch (result) {
      case 11:
        result += 5;
        break selected;
      default:
        result += 100;
    }
    return result;
  }

  public static void main(String[] args) {
    System.out.print(
        labeledBlocksAndIf()
            + "|"
            + nestedLoops()
            + "|"
            + everyLoopKind()
            + "|"
            + singleStatementLoop()
            + "|"
            + disjointLabelReuse()
            + "|"
            + nestedDistinctLabels()
            + "|"
            + tryFinally()
            + "|"
            + branchFromFinally()
            + "|"
            + switchInteraction());
  }
}
