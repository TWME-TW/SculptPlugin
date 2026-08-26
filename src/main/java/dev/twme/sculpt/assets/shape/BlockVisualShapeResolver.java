package dev.twme.sculpt.assets.shape;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.twme.sculpt.assets.fetch.McAssetClient;

/** Resolves a complete vanilla BlockData string into its rendered grid-16 shape. */
public final class BlockVisualShapeResolver {

    private static final int MAX_MODEL_CHAIN = 64;
    private static final int MAX_ELEMENTS = 512;
    private static final int MAX_ALTERNATIVES = 128;
    private static final double EPSILON = 1.0e-7;
    private static final double MIN_MODEL_COORDINATE = -32.0;
    private static final double MAX_MODEL_COORDINATE = 48.0;

    private final AssetSource assets;
    private final Logger logger;
    private final ConcurrentMap<String, ResolvedModel> modelCache =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AlphaTexture> textureCache =
        new ConcurrentHashMap<>();

    public BlockVisualShapeResolver(
            final McAssetClient client,
            final String minecraftVersion,
            final Logger logger) {
        this(path -> client.fetch(minecraftVersion, path), logger);
    }

    /** Constructor used by deterministic tests and offline asset providers. */
    public BlockVisualShapeResolver(
            final AssetSource assets,
            final Logger logger) {
        this.assets = assets;
        this.logger = logger;
    }

    public Resolution resolve(final String blockDataString) {
        try {
            final BlockState state = BlockState.parse(blockDataString);
            if (!"minecraft".equals(state.namespace())) {
                return Resolution.unsupported(Failure.NON_VANILLA,
                    state.namespace() + ":" + state.path());
            }
            final JsonObject blockstate = parseObject(assets.fetchString(
                "blockstates/" + state.path() + ".json"));
            final VisualShape shape = resolveBlockstate(blockstate, state.properties());
            if (shape.isEmpty()) {
                return Resolution.unsupported(Failure.EMPTY_MODEL, state.path());
            }
            return Resolution.supported(shape);
        } catch (final UnsupportedShapeException unsupported) {
            return Resolution.unsupported(unsupported.failure, unsupported.getMessage());
        } catch (final IllegalArgumentException invalid) {
            return Resolution.unsupported(Failure.INVALID_BLOCK_DATA, invalid.getMessage());
        } catch (final IOException assetFailure) {
            return Resolution.unsupported(Failure.ASSET_ERROR, assetFailure.getMessage());
        } catch (final RuntimeException unexpected) {
            if (logger != null) {
                logger.log(Level.WARNING,
                    "[visual-shape] unexpected error resolving " + blockDataString,
                    unexpected);
            }
            return Resolution.unsupported(Failure.INVALID_MODEL,
                unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        }
    }

    private VisualShape resolveBlockstate(
            final JsonObject blockstate,
            final Map<String, String> properties)
            throws IOException, UnsupportedShapeException {
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            return resolveVariants(blockstate.getAsJsonObject("variants"), properties);
        }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            return resolveMultipart(blockstate.getAsJsonArray("multipart"), properties);
        }
        throw unsupported(Failure.INVALID_MODEL,
            "blockstate has neither variants nor multipart");
    }

    private VisualShape resolveVariants(
            final JsonObject variants,
            final Map<String, String> properties)
            throws IOException, UnsupportedShapeException {
        final List<JsonElement> best = new ArrayList<>();
        int bestSpecificity = -1;
        for (final Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            final VariantCondition condition = VariantCondition.parse(entry.getKey());
            if (!condition.matches(properties)) continue;
            if (condition.specificity() > bestSpecificity) {
                best.clear();
                bestSpecificity = condition.specificity();
            }
            if (condition.specificity() == bestSpecificity) best.add(entry.getValue());
        }
        if (best.isEmpty()) {
            throw unsupported(Failure.NO_MATCHING_STATE,
                "no variant matches " + properties);
        }

        VisualShape selected = null;
        for (final JsonElement candidate : best) {
            final VisualShape shape = resolveAlternatives(candidate);
            if (selected == null) selected = shape;
            else if (!selected.equals(shape)) {
                throw unsupported(Failure.AMBIGUOUS_WEIGHTED_MODEL,
                    "equally specific variants have different shapes");
            }
        }
        return selected;
    }

    private VisualShape resolveMultipart(
            final JsonArray multipart,
            final Map<String, String> properties)
            throws IOException, UnsupportedShapeException {
        final VisualShape.Builder combined = VisualShape.builder();
        int matched = 0;
        for (final JsonElement partElement : multipart) {
            if (!partElement.isJsonObject()) continue;
            final JsonObject part = partElement.getAsJsonObject();
            if (part.has("when") && !matchesWhen(part.get("when"), properties)) {
                continue;
            }
            if (!part.has("apply")) {
                throw unsupported(Failure.INVALID_MODEL,
                    "multipart entry has no apply value");
            }
            combined.add(resolveAlternatives(part.get("apply")));
            matched++;
        }
        if (matched == 0) {
            throw unsupported(Failure.NO_MATCHING_STATE,
                "no multipart entry matches " + properties);
        }
        return combined.build();
    }

    private boolean matchesWhen(
            final JsonElement condition,
            final Map<String, String> properties)
            throws UnsupportedShapeException {
        if (condition == null || condition.isJsonNull()) return true;
        if (condition.isJsonArray()) {
            for (final JsonElement child : condition.getAsJsonArray()) {
                if (!matchesWhen(child, properties)) return false;
            }
            return true;
        }
        if (!condition.isJsonObject()) {
            throw unsupported(Failure.INVALID_MODEL,
                "multipart condition is not an object");
        }

        final JsonObject object = condition.getAsJsonObject();
        for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if ("OR".equals(entry.getKey())) {
                if (!entry.getValue().isJsonArray()) return false;
                boolean any = false;
                for (final JsonElement child : entry.getValue().getAsJsonArray()) {
                    if (matchesWhen(child, properties)) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
                continue;
            }
            if ("AND".equals(entry.getKey())) {
                if (!matchesWhen(entry.getValue(), properties)) return false;
                continue;
            }
            if (!entry.getValue().isJsonPrimitive()
                    || !matchesProperty(properties.get(entry.getKey()),
                        entry.getValue().getAsString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesProperty(
            final String actual,
            final String expression) {
        if (actual == null) return false;
        for (final String rawAlternative : expression.split("\\|")) {
            final String alternative = rawAlternative.trim();
            if (alternative.startsWith("!")) {
                if (!actual.equals(alternative.substring(1))) return true;
            } else if (actual.equals(alternative)) {
                return true;
            }
        }
        return false;
    }

    private VisualShape resolveAlternatives(final JsonElement value)
            throws IOException, UnsupportedShapeException {
        final List<ModelApplication> alternatives = parseApplications(value);
        if (alternatives.isEmpty()) {
            throw unsupported(Failure.INVALID_MODEL, "empty model application");
        }
        VisualShape selected = null;
        for (final ModelApplication application : alternatives) {
            final VisualShape shape = rasterize(application);
            if (selected == null) selected = shape;
            else if (!selected.equals(shape)) {
                throw unsupported(Failure.AMBIGUOUS_WEIGHTED_MODEL,
                    "weighted alternatives have different visual shapes");
            }
        }
        return selected;
    }

    private static List<ModelApplication> parseApplications(final JsonElement value)
            throws UnsupportedShapeException {
        final List<ModelApplication> applications = new ArrayList<>();
        if (value.isJsonArray()) {
            if (value.getAsJsonArray().size() > MAX_ALTERNATIVES) {
                throw unsupported(Failure.INVALID_MODEL,
                    "too many model alternatives");
            }
            for (final JsonElement element : value.getAsJsonArray()) {
                applications.add(parseApplication(element));
            }
        } else {
            applications.add(parseApplication(value));
        }
        return applications;
    }

    private static ModelApplication parseApplication(final JsonElement value)
            throws UnsupportedShapeException {
        if (!value.isJsonObject()) {
            throw unsupported(Failure.INVALID_MODEL,
                "model application is not an object");
        }
        final JsonObject object = value.getAsJsonObject();
        if (!object.has("model") || !object.get("model").isJsonPrimitive()) {
            throw unsupported(Failure.INVALID_MODEL,
                "model application has no model id");
        }
        return new ModelApplication(
            normalizeId(object.get("model").getAsString(), "block/"),
            number(object, "x", 0.0),
            number(object, "y", 0.0));
    }

    private VisualShape rasterize(final ModelApplication application)
            throws IOException, UnsupportedShapeException {
        final ResolvedModel model = resolveModel(
            application.model(), new HashSet<>(), 0);
        if (model.elements().isEmpty()) {
            throw unsupported(Failure.NO_ELEMENTS,
                "model has no JSON elements: " + application.model());
        }
        final VisualShape.Builder shape = VisualShape.builder();
        for (final ModelElement element : model.elements()) {
            rasterizeElement(shape, element, model.textures(), application);
        }
        return shape.build();
    }

    private ResolvedModel resolveModel(
            final String modelId,
            final Set<String> visiting,
            final int depth)
            throws IOException, UnsupportedShapeException {
        final String normalized = normalizeId(modelId, "block/");
        final ResolvedModel cached = modelCache.get(normalized);
        if (cached != null) return cached;
        if (depth >= MAX_MODEL_CHAIN || !visiting.add(normalized)) {
            throw unsupported(Failure.INVALID_MODEL,
                "cyclic or over-deep model parent chain: " + normalized);
        }

        final Id id = Id.parse(normalized);
        if (!"minecraft".equals(id.namespace())) {
            throw unsupported(Failure.NON_VANILLA, normalized);
        }
        final JsonObject json = parseObject(assets.fetchString(
            "models/" + id.path() + ".json"));

        ResolvedModel parent = null;
        if (json.has("parent") && json.get("parent").isJsonPrimitive()) {
            final String parentId = normalizeId(json.get("parent").getAsString(), "block/");
            if (!parentId.contains(":builtin/")) {
                parent = resolveModel(parentId, visiting, depth + 1);
            }
        }

        final Map<String, String> textures = new LinkedHashMap<>();
        if (parent != null) textures.putAll(parent.textures());
        if (json.has("textures") && json.get("textures").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry
                    : json.getAsJsonObject("textures").entrySet()) {
                final String texture = textureSlot(entry.getValue());
                if (texture != null) textures.put(entry.getKey(), texture);
            }
        }

        final List<ModelElement> elements;
        if (json.has("elements") && json.get("elements").isJsonArray()) {
            elements = parseElements(json.getAsJsonArray("elements"));
        } else if (parent != null) {
            elements = parent.elements();
        } else {
            // Abstract display/template parents such as block/block define no
            // geometry themselves. A child may still provide valid elements.
            elements = List.of();
        }

        visiting.remove(normalized);
        final ResolvedModel resolved = new ResolvedModel(
            List.copyOf(elements), Map.copyOf(textures));
        final ResolvedModel raced = modelCache.putIfAbsent(normalized, resolved);
        return raced == null ? resolved : raced;
    }

    private static List<ModelElement> parseElements(final JsonArray array)
            throws UnsupportedShapeException {
        if (array.size() > MAX_ELEMENTS) {
            throw unsupported(Failure.INVALID_MODEL, "too many model elements");
        }
        final List<ModelElement> elements = new ArrayList<>();
        for (final JsonElement elementValue : array) {
            if (!elementValue.isJsonObject()) {
                throw unsupported(Failure.INVALID_MODEL,
                    "model element is not an object");
            }
            final JsonObject element = elementValue.getAsJsonObject();
            final Vec3 from = vector(element.get("from"), "from");
            final Vec3 to = vector(element.get("to"), "to");
            final Vec3 minimum = new Vec3(
                Math.min(from.x(), to.x()),
                Math.min(from.y(), to.y()),
                Math.min(from.z(), to.z()));
            final Vec3 maximum = new Vec3(
                Math.max(from.x(), to.x()),
                Math.max(from.y(), to.y()),
                Math.max(from.z(), to.z()));

            ElementRotation rotation = null;
            if (element.has("rotation") && element.get("rotation").isJsonObject()) {
                final JsonObject value = element.getAsJsonObject("rotation");
                final Vec3 origin = vector(value.get("origin"), "rotation.origin");
                final Vec3 angles;
                if (value.has("axis") || value.has("angle")) {
                    final Axis axis;
                    try {
                        axis = Axis.valueOf(value.get("axis").getAsString()
                            .toUpperCase(Locale.ROOT));
                    } catch (final RuntimeException invalidAxis) {
                        throw unsupported(Failure.INVALID_MODEL,
                            "invalid element rotation axis");
                    }
                    final double angle = number(value, "angle", 0.0);
                    angles = switch (axis) {
                        case X -> new Vec3(angle, 0.0, 0.0);
                        case Y -> new Vec3(0.0, angle, 0.0);
                        case Z -> new Vec3(0.0, 0.0, angle);
                    };
                } else if (value.has("x") || value.has("y") || value.has("z")) {
                    angles = new Vec3(number(value, "x", 0.0),
                        number(value, "y", 0.0), number(value, "z", 0.0));
                } else {
                    throw unsupported(Failure.INVALID_MODEL,
                        "element rotation has no axis-angle or Euler value");
                }
                final boolean rescale = value.has("rescale")
                    && value.get("rescale").getAsBoolean();
                rotation = new ElementRotation(origin, angles,
                    rescale ? rescaleFor(angles) : Vec3.ONE);
            }

            final Map<Direction, ModelFace> faces = new EnumMap<>(Direction.class);
            if (element.has("faces") && element.get("faces").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> faceEntry
                        : element.getAsJsonObject("faces").entrySet()) {
                    final Direction direction;
                    try {
                        direction = Direction.valueOf(
                            faceEntry.getKey().toUpperCase(Locale.ROOT));
                    } catch (final IllegalArgumentException ignored) {
                        continue;
                    }
                    if (!faceEntry.getValue().isJsonObject()) continue;
                    final JsonObject face = faceEntry.getValue().getAsJsonObject();
                    if (!face.has("texture") || !face.get("texture").isJsonPrimitive()) {
                        continue;
                    }
                    final double[] uv = face.has("uv")
                        ? vector4(face.get("uv"), "face.uv") : null;
                    faces.put(direction, new ModelFace(
                        face.get("texture").getAsString(), uv,
                        (int) number(face, "rotation", 0.0)));
                }
            }
            elements.add(new ModelElement(minimum, maximum, rotation, Map.copyOf(faces)));
        }
        return elements;
    }

    private void rasterizeElement(
            final VisualShape.Builder output,
            final ModelElement element,
            final Map<String, String> textures,
            final ModelApplication application)
            throws IOException, UnsupportedShapeException {
        final int degenerateAxes = element.degenerateAxes();
        if (degenerateAxes > 1) {
            throw unsupported(Failure.UNSUPPORTED_DEGENERATE_ELEMENT,
                "line or point model element cannot be represented");
        }

        Vec3 boundsMin = new Vec3(
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 boundsMax = new Vec3(
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (int corner = 0; corner < 8; corner++) {
            final Vec3 point = new Vec3(
                (corner & 4) == 0 ? element.minimum().x() : element.maximum().x(),
                (corner & 2) == 0 ? element.minimum().y() : element.maximum().y(),
                (corner & 1) == 0 ? element.minimum().z() : element.maximum().z());
            final Vec3 transformed = forward(point, element.rotation(), application);
            boundsMin = boundsMin.minimum(transformed);
            boundsMax = boundsMax.maximum(transformed);
        }
        if (boundsMin.x() < MIN_MODEL_COORDINATE
                || boundsMin.y() < MIN_MODEL_COORDINATE
                || boundsMin.z() < MIN_MODEL_COORDINATE
                || boundsMax.x() > MAX_MODEL_COORDINATE
                || boundsMax.y() > MAX_MODEL_COORDINATE
                || boundsMax.z() > MAX_MODEL_COORDINATE) {
            throw unsupported(Failure.OUT_OF_RANGE,
                "transformed model extends outside the supported range");
        }

        final int minX = (int) Math.floor(boundsMin.x()) - 1;
        final int minY = (int) Math.floor(boundsMin.y()) - 1;
        final int minZ = (int) Math.floor(boundsMin.z()) - 1;
        final int maxX = (int) Math.ceil(boundsMax.x()) + 1;
        final int maxY = (int) Math.ceil(boundsMax.y()) + 1;
        final int maxZ = (int) Math.ceil(boundsMax.z()) + 1;
        final double planeSupport = degenerateAxes == 1
            ? planeSupport(element, application) : 0.0;

        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    final Vec3 local = inverse(
                        new Vec3(x + 0.5, y + 0.5, z + 0.5),
                        element.rotation(), application);
                    final boolean occupied = degenerateAxes == 0
                        ? element.contains(local)
                        : visiblePlaneAt(element, textures, local, planeSupport);
                    if (occupied) output.setVoxel(x, y, z);
                }
            }
        }
    }

    private boolean visiblePlaneAt(
            final ModelElement element,
            final Map<String, String> textures,
            final Vec3 point,
            final double support)
            throws IOException, UnsupportedShapeException {
        final Axis axis = element.degenerateAxis();
        final double plane = element.minimum().component(axis);
        final double signedDistance = point.component(axis) - plane;
        if (Math.abs(signedDistance) > support + EPSILON) return false;
        if (Math.abs(Math.abs(signedDistance) - support) <= EPSILON
                && signedDistance < 0.0) return false;
        if (!element.containsOnOtherAxes(point, axis)) return false;

        for (final Direction direction : Direction.forAxis(axis)) {
            final ModelFace face = element.faces().get(direction);
            if (face == null) continue;
            // Both directions describe the same physical plane. Sampling both
            // and taking a union can mirror asymmetric alpha and fill its holes.
            return faceOpaqueAt(face, direction, element, textures, point);
        }
        throw unsupported(Failure.INVALID_MODEL,
            "zero-thickness element has no face on its plane");
    }

    private boolean faceOpaqueAt(
            final ModelFace face,
            final Direction direction,
            final ModelElement element,
            final Map<String, String> textures,
            final Vec3 point)
            throws IOException, UnsupportedShapeException {
        double u;
        double v;
        switch (direction.axis()) {
            case X -> {
                u = fraction(point.z(), element.minimum().z(), element.maximum().z());
                v = 1.0 - fraction(point.y(), element.minimum().y(), element.maximum().y());
                if (direction == Direction.EAST) u = 1.0 - u;
            }
            case Y -> {
                u = fraction(point.x(), element.minimum().x(), element.maximum().x());
                v = fraction(point.z(), element.minimum().z(), element.maximum().z());
                if (direction == Direction.DOWN) v = 1.0 - v;
            }
            case Z -> {
                u = fraction(point.x(), element.minimum().x(), element.maximum().x());
                v = 1.0 - fraction(point.y(), element.minimum().y(), element.maximum().y());
                if (direction == Direction.NORTH) u = 1.0 - u;
            }
            default -> throw new IllegalStateException("unreachable axis");
        }
        final double[] rotated = rotateUv(clamp01(u), clamp01(v), face.rotation());
        final double[] uv = face.uv() == null
            ? new double[]{0.0, 0.0, 16.0, 16.0} : face.uv();
        final double textureU = uv[0] + (uv[2] - uv[0]) * rotated[0];
        final double textureV = uv[1] + (uv[3] - uv[1]) * rotated[1];
        return texture(resolveTexture(face.texture(), textures))
            .opaque(textureU, textureV);
    }

    private AlphaTexture texture(final String textureId)
            throws IOException, UnsupportedShapeException {
        final String normalized = normalizeId(textureId, "block/");
        final AlphaTexture cached = textureCache.get(normalized);
        if (cached != null) return cached;
        final Id id = Id.parse(normalized);
        if (!"minecraft".equals(id.namespace())) {
            throw unsupported(Failure.NON_VANILLA, normalized);
        }
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(
            assets.fetch("textures/" + id.path() + ".png")));
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw unsupported(Failure.INVALID_MODEL,
                "invalid texture image: " + normalized);
        }
        final AlphaTexture loaded = new AlphaTexture(image);
        final AlphaTexture raced = textureCache.putIfAbsent(normalized, loaded);
        return raced == null ? loaded : raced;
    }

    private static String resolveTexture(
            final String reference,
            final Map<String, String> textures)
            throws UnsupportedShapeException {
        String value = reference;
        final Set<String> visited = new HashSet<>();
        while (value.startsWith("#")) {
            final String key = value.substring(1);
            if (!visited.add(key)) {
                throw unsupported(Failure.INVALID_MODEL,
                    "cyclic texture reference: " + reference);
            }
            value = textures.get(key);
            if (value == null) {
                throw unsupported(Failure.INVALID_MODEL,
                    "unresolved texture reference: #" + key);
            }
        }
        return value;
    }

    /** Resolve both legacy string slots and Minecraft 26.2 sprite objects. */
    private static String textureSlot(final JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        if (!value.isJsonObject()) return null;
        final JsonElement sprite = value.getAsJsonObject().get("sprite");
        return sprite != null && sprite.isJsonPrimitive()
                && sprite.getAsJsonPrimitive().isString()
            ? sprite.getAsString() : null;
    }

    private static double planeSupport(
            final ModelElement element,
            final ModelApplication application) {
        final Axis axis = element.degenerateAxis();
        final Vec3 origin = new Vec3(0.0, 0.0, 0.0);
        final Vec3 normal = switch (axis) {
            case X -> new Vec3(1.0, 0.0, 0.0);
            case Y -> new Vec3(0.0, 1.0, 0.0);
            case Z -> new Vec3(0.0, 0.0, 1.0);
        };
        final Vec3 transformedOrigin = forwardVector(origin, element.rotation(), application);
        final Vec3 transformedNormal = forwardVector(normal, element.rotation(), application)
            .subtract(transformedOrigin).normalized();
        return 0.5 * (Math.abs(transformedNormal.x())
            + Math.abs(transformedNormal.y()) + Math.abs(transformedNormal.z()));
    }

    private static Vec3 forward(
            final Vec3 point,
            final ElementRotation rotation,
            final ModelApplication application) {
        Vec3 result = point;
        if (rotation != null) {
            result = rotateEuler(
                result.subtract(rotation.origin()).scale(rotation.scale()),
                rotation.angles())
                .add(rotation.origin());
        }
        result = result.subtract(Vec3.CENTER)
            .rotate(Axis.X, application.xRotation())
            .rotate(Axis.Y, application.yRotation())
            .add(Vec3.CENTER);
        return result;
    }

    private static Vec3 forwardVector(
            final Vec3 vector,
            final ElementRotation rotation,
            final ModelApplication application) {
        Vec3 result = vector;
        if (rotation != null) {
            result = rotateEuler(result.scale(rotation.scale()), rotation.angles());
        }
        return result.rotate(Axis.X, application.xRotation())
            .rotate(Axis.Y, application.yRotation());
    }

    private static Vec3 inverse(
            final Vec3 point,
            final ElementRotation rotation,
            final ModelApplication application) {
        Vec3 result = point.subtract(Vec3.CENTER)
            .rotate(Axis.Y, -application.yRotation())
            .rotate(Axis.X, -application.xRotation())
            .add(Vec3.CENTER);
        if (rotation != null) {
            result = inverseRotateEuler(
                result.subtract(rotation.origin()), rotation.angles())
                .divide(rotation.scale()).add(rotation.origin());
        }
        return result;
    }

    private static Vec3 rotateEuler(final Vec3 vector, final Vec3 angles) {
        return vector.rotate(Axis.X, angles.x())
            .rotate(Axis.Y, angles.y())
            .rotate(Axis.Z, angles.z());
    }

    private static Vec3 inverseRotateEuler(final Vec3 vector, final Vec3 angles) {
        return vector.rotate(Axis.Z, -angles.z())
            .rotate(Axis.Y, -angles.y())
            .rotate(Axis.X, -angles.x());
    }

    /** Mirrors Minecraft's CuboidRotation rescale calculation. */
    private static Vec3 rescaleFor(final Vec3 angles) {
        return new Vec3(
            reciprocalLargestComponent(rotateEuler(new Vec3(1.0, 0.0, 0.0), angles)),
            reciprocalLargestComponent(rotateEuler(new Vec3(0.0, 1.0, 0.0), angles)),
            reciprocalLargestComponent(rotateEuler(new Vec3(0.0, 0.0, 1.0), angles)));
    }

    private static double reciprocalLargestComponent(final Vec3 vector) {
        final double largest = Math.max(Math.abs(vector.x()),
            Math.max(Math.abs(vector.y()), Math.abs(vector.z())));
        return largest <= EPSILON ? 1.0 : 1.0 / largest;
    }

    private static double fraction(
            final double value,
            final double minimum,
            final double maximum) {
        final double span = maximum - minimum;
        return span <= EPSILON ? 0.5 : (value - minimum) / span;
    }

    private static double clamp01(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double[] rotateUv(
            final double u,
            final double v,
            final int rawRotation) {
        final int rotation = Math.floorMod(rawRotation, 360);
        return switch (rotation) {
            case 0 -> new double[]{u, v};
            case 90 -> new double[]{v, 1.0 - u};
            case 180 -> new double[]{1.0 - u, 1.0 - v};
            case 270 -> new double[]{1.0 - v, u};
            default -> new double[]{u, v};
        };
    }

    private static JsonObject parseObject(final String json)
            throws UnsupportedShapeException {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (final RuntimeException invalidJson) {
            throw unsupported(Failure.INVALID_MODEL,
                "invalid JSON: " + invalidJson.getMessage());
        }
        if (!parsed.isJsonObject()) {
            throw unsupported(Failure.INVALID_MODEL, "asset root is not an object");
        }
        return parsed.getAsJsonObject();
    }

    private static Vec3 vector(final JsonElement value, final String name)
            throws UnsupportedShapeException {
        if (value == null || !value.isJsonArray()
                || value.getAsJsonArray().size() != 3) {
            throw unsupported(Failure.INVALID_MODEL,
                name + " must contain three coordinates");
        }
        final JsonArray array = value.getAsJsonArray();
        return new Vec3(array.get(0).getAsDouble(),
            array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static double[] vector4(final JsonElement value, final String name)
            throws UnsupportedShapeException {
        if (value == null || !value.isJsonArray()
                || value.getAsJsonArray().size() != 4) {
            throw unsupported(Failure.INVALID_MODEL,
                name + " must contain four coordinates");
        }
        final JsonArray array = value.getAsJsonArray();
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(),
            array.get(2).getAsDouble(), array.get(3).getAsDouble()};
    }

    private static double number(
            final JsonObject object,
            final String key,
            final double fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
            ? object.get(key).getAsDouble() : fallback;
    }

    private static String normalizeId(
            final String raw,
            final String defaultDirectory) {
        final String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        final int separator = trimmed.indexOf(':');
        final String namespace = separator < 0
            ? "minecraft" : trimmed.substring(0, separator);
        String path = separator < 0 ? trimmed : trimmed.substring(separator + 1);
        if (!path.contains("/") && defaultDirectory != null) {
            path = defaultDirectory + path;
        }
        return namespace + ":" + path;
    }

    private static UnsupportedShapeException unsupported(
            final Failure failure,
            final String message) {
        return new UnsupportedShapeException(failure, message);
    }

    @FunctionalInterface
    public interface AssetSource {
        byte[] fetch(String path) throws IOException;

        default String fetchString(final String path) throws IOException {
            return new String(fetch(path), StandardCharsets.UTF_8);
        }
    }

    public enum Failure {
        INVALID_BLOCK_DATA,
        NON_VANILLA,
        ASSET_ERROR,
        INVALID_MODEL,
        NO_MATCHING_STATE,
        NO_ELEMENTS,
        EMPTY_MODEL,
        AMBIGUOUS_WEIGHTED_MODEL,
        UNSUPPORTED_DEGENERATE_ELEMENT,
        OUT_OF_RANGE
    }

    public record Resolution(VisualShape shape, Failure failure, String detail) {
        public Resolution {
            if ((shape == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "exactly one of shape or failure must be present");
            }
            detail = detail == null ? "" : detail;
        }

        public static Resolution supported(final VisualShape shape) {
            return new Resolution(shape, null, "");
        }

        public static Resolution unsupported(
                final Failure failure,
                final String detail) {
            return new Resolution(null, failure, detail);
        }

        public boolean supported() {
            return shape != null;
        }
    }

    private record Id(String namespace, String path) {
        private static Id parse(final String normalized) {
            final int separator = normalized.indexOf(':');
            if (separator <= 0 || separator == normalized.length() - 1) {
                throw new IllegalArgumentException("invalid resource id: " + normalized);
            }
            return new Id(normalized.substring(0, separator),
                normalized.substring(separator + 1));
        }
    }

    private record BlockState(
        String namespace,
        String path,
        Map<String, String> properties
    ) {
        private static BlockState parse(final String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("empty BlockData");
            }
            final String normalized = value.trim().toLowerCase(Locale.ROOT);
            final int stateStart = normalized.indexOf('[');
            final String idValue = stateStart < 0
                ? normalized : normalized.substring(0, stateStart);
            final Id id = Id.parse(idValue.contains(":")
                ? idValue : "minecraft:" + idValue);
            final Map<String, String> properties = new LinkedHashMap<>();
            if (stateStart >= 0) {
                if (!normalized.endsWith("]")) {
                    throw new IllegalArgumentException("unterminated BlockData properties");
                }
                final String body = normalized.substring(
                    stateStart + 1, normalized.length() - 1);
                if (!body.isBlank()) {
                    for (final String assignment : body.split(",")) {
                        final int equals = assignment.indexOf('=');
                        if (equals <= 0 || equals == assignment.length() - 1) {
                            throw new IllegalArgumentException(
                                "invalid BlockData property: " + assignment);
                        }
                        properties.put(assignment.substring(0, equals).trim(),
                            assignment.substring(equals + 1).trim());
                    }
                }
            }
            return new BlockState(id.namespace(), id.path(), Map.copyOf(properties));
        }
    }

    private record VariantCondition(Map<String, String> properties) {
        private static VariantCondition parse(final String value) {
            final Map<String, String> properties = new HashMap<>();
            if (value != null && !value.isBlank()) {
                for (final String assignment : value.split(",")) {
                    final int equals = assignment.indexOf('=');
                    if (equals > 0 && equals < assignment.length() - 1) {
                        properties.put(assignment.substring(0, equals).trim(),
                            assignment.substring(equals + 1).trim());
                    }
                }
            }
            return new VariantCondition(Map.copyOf(properties));
        }

        private boolean matches(final Map<String, String> actual) {
            for (final Map.Entry<String, String> entry : properties.entrySet()) {
                if (!matchesProperty(actual.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private int specificity() {
            return properties.size();
        }
    }

    private record ModelApplication(
        String model,
        double xRotation,
        double yRotation
    ) {}

    private record ResolvedModel(
        List<ModelElement> elements,
        Map<String, String> textures
    ) {}

    private record ModelElement(
        Vec3 minimum,
        Vec3 maximum,
        ElementRotation rotation,
        Map<Direction, ModelFace> faces
    ) {
        private int degenerateAxes() {
            int count = 0;
            if (maximum.x() - minimum.x() <= EPSILON) count++;
            if (maximum.y() - minimum.y() <= EPSILON) count++;
            if (maximum.z() - minimum.z() <= EPSILON) count++;
            return count;
        }

        private Axis degenerateAxis() {
            if (maximum.x() - minimum.x() <= EPSILON) return Axis.X;
            if (maximum.y() - minimum.y() <= EPSILON) return Axis.Y;
            return Axis.Z;
        }

        private boolean contains(final Vec3 point) {
            return containsAxis(point.x(), minimum.x(), maximum.x())
                && containsAxis(point.y(), minimum.y(), maximum.y())
                && containsAxis(point.z(), minimum.z(), maximum.z());
        }

        private static boolean containsAxis(
                final double value,
                final double minimum,
                final double maximum) {
            final double span = maximum - minimum;
            if (span > EPSILON && span < 1.0 - EPSILON) {
                final double quantizedMinimum = Math.floor(minimum + EPSILON);
                final double quantizedMaximum = Math.ceil(maximum - EPSILON);
                return value >= quantizedMinimum - EPSILON
                    && value < quantizedMaximum - EPSILON;
            }
            return value >= minimum - EPSILON && value < maximum - EPSILON;
        }

        private boolean containsOnOtherAxes(final Vec3 point, final Axis ignored) {
            return (ignored == Axis.X || inRange(point.x(), minimum.x(), maximum.x()))
                && (ignored == Axis.Y || inRange(point.y(), minimum.y(), maximum.y()))
                && (ignored == Axis.Z || inRange(point.z(), minimum.z(), maximum.z()));
        }

        private static boolean inRange(
                final double value,
                final double minimum,
                final double maximum) {
            return value >= minimum - EPSILON && value < maximum - EPSILON;
        }
    }

    private record ModelFace(String texture, double[] uv, int rotation) {
        private ModelFace {
            uv = uv == null ? null : uv.clone();
        }

        @Override
        public double[] uv() {
            return uv == null ? null : uv.clone();
        }
    }

    private record ElementRotation(Vec3 origin, Vec3 angles, Vec3 scale) {}

    private enum Axis { X, Y, Z }

    private enum Direction {
        DOWN(Axis.Y), UP(Axis.Y),
        NORTH(Axis.Z), SOUTH(Axis.Z),
        WEST(Axis.X), EAST(Axis.X);

        private final Axis axis;

        Direction(final Axis axis) {
            this.axis = axis;
        }

        private Axis axis() {
            return axis;
        }

        private static List<Direction> forAxis(final Axis axis) {
            return switch (axis) {
                case X -> List.of(WEST, EAST);
                case Y -> List.of(DOWN, UP);
                case Z -> List.of(NORTH, SOUTH);
            };
        }
    }

    private record Vec3(double x, double y, double z) {
        private static final Vec3 CENTER = new Vec3(8.0, 8.0, 8.0);
        private static final Vec3 ONE = new Vec3(1.0, 1.0, 1.0);

        private Vec3 add(final Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        private Vec3 subtract(final Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        private Vec3 minimum(final Vec3 other) {
            return new Vec3(Math.min(x, other.x), Math.min(y, other.y),
                Math.min(z, other.z));
        }

        private Vec3 maximum(final Vec3 other) {
            return new Vec3(Math.max(x, other.x), Math.max(y, other.y),
                Math.max(z, other.z));
        }

        private double component(final Axis axis) {
            return switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
        }

        private Vec3 scale(final Vec3 scale) {
            return new Vec3(x * scale.x, y * scale.y, z * scale.z);
        }

        private Vec3 divide(final Vec3 divisor) {
            return new Vec3(x / divisor.x, y / divisor.y, z / divisor.z);
        }

        private Vec3 rotate(final Axis axis, final double degrees) {
            if (Math.abs(degrees) <= EPSILON) return this;
            final double radians = Math.toRadians(degrees);
            final double cosine = Math.cos(radians);
            final double sine = Math.sin(radians);
            return switch (axis) {
                case X -> new Vec3(x,
                    y * cosine - z * sine,
                    y * sine + z * cosine);
                case Y -> new Vec3(
                    x * cosine + z * sine,
                    y,
                    -x * sine + z * cosine);
                case Z -> new Vec3(
                    x * cosine - y * sine,
                    x * sine + y * cosine,
                    z);
            };
        }

        private Vec3 normalized() {
            final double length = Math.sqrt(x * x + y * y + z * z);
            return length <= EPSILON ? this
                : new Vec3(x / length, y / length, z / length);
        }
    }

    private record AlphaTexture(BufferedImage image) {
        private boolean opaque(final double u, final double v) {
            final int frameHeight = Math.min(image.getHeight(), image.getWidth());
            final int x = Math.max(0, Math.min(image.getWidth() - 1,
                (int) Math.floor(clamp01(u / 16.0) * image.getWidth())));
            final int y = Math.max(0, Math.min(frameHeight - 1,
                (int) Math.floor(clamp01(v / 16.0) * frameHeight)));
            return ((image.getRGB(x, y) >>> 24) & 0xff) > 16;
        }
    }

    private static final class UnsupportedShapeException extends Exception {
        private final Failure failure;

        private UnsupportedShapeException(
                final Failure failure,
                final String message) {
            super(message);
            this.failure = failure;
        }
    }
}
