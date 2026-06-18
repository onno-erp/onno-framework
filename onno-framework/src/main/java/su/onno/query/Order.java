package su.onno.query;

/** An ORDER BY term over a {@link Path}. */
public record Order(Path path, Direction direction) {

    public enum Direction { ASC, DESC }

    public Order {
        if (path == null) throw new IllegalArgumentException("Order needs a path");
        if (direction == null) direction = Direction.ASC;
    }

    Order withRoot(Class<?> root) {
        return new Order(path.withRoot(root), direction);
    }
}
