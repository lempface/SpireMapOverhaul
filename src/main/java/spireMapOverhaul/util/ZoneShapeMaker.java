package spireMapOverhaul.util;

import basemod.Pair;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.map.MapRoomNode;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.type.ImFloat;
import spireMapOverhaul.BetterMapGenerator;
import spireMapOverhaul.SpireAnniversary6Mod;
import spireMapOverhaul.abstracts.AbstractZone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

public class ZoneShapeMaker {
    private static final float SPACING_X = Settings.isMobile ? (int)(Settings.xScale * 64.0F) * 2.2F : (int)(Settings.xScale * 64.0F) * 2.0F;

    //adjusts size and scaling of initial circles
    private static float MAX_NODE_SPACING = 450f * Settings.scale;
    private static float CIRCLE_SIZE_MULT = 0.875f;
    private static float MAX_CIRCLE_SCALE = 2.5f;

    private static float FIRST_PASS_STEPSIZE = 7f;  //1 = max quality, x1 range, 2 = lower quality, x2 range
    private static float FIRST_PASS_SUBDIV = 6f;  //= range when multiplied by stepSize, lowers performance quadratically
    private static float FIRST_PASS_DIVIDER = 0.9f;   //higher = more range, more "amalgamation", but edges get rougher
    private static float FIRST_PASS_WHITENING = 1f;//0.5f; //Adds to color of existing pixels.
    private static float FIRST_PASS_DARKENING = 0f; //Multiplies color of existing pixels

    private static float SECOND_PASS_STEPSIZE = 8f;
    private static float SECOND_PASS_SUBDIV = 4f;
    private static float SECOND_PASS_DIVIDER = 1.2f;
    private static float SECOND_PASS_WHITENING = 0.65f;
    private static float SECOND_PASS_DARKENING = 0.2f; //0.9f;

    private static float SMOOTHING_STEPSIZE = 1f;
    private static float SMOOTHING_SUBDIV = 8f;
    private static float SMOOTHING_DIVIDER = 1.25f;
    private static float SMOOTHING_WHITENING = 0.4f; //0f;
    private static float SMOOTHING_DARKENING = 0.4f; //0.8f;

    public static final int FB_OFFSET = (int) (150 * Settings.scale); //Offset of positioning of nodes to fit circles
    private static final int FB_MARGIN = (int) (FB_OFFSET * 2.5f);

    private static final ShaderProgram shader = new ShaderProgram(Gdx.files.internal("anniv6Resources/shaders/shapeMaker/vertex.vs"),
                                                            Gdx.files.internal("anniv6Resources/shaders/shapeMaker/fragment.fs"));
    private static FrameBuffer commonBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, 512, 512, false);
    private static final Matrix4 fbProjection = new Matrix4(), tempMatrix = new Matrix4();
    private static final TextureRegion CIRCLE = new TextureRegion(TexLoader.getTexture(SpireAnniversary6Mod.makeImagePath("ui/WhiteCircle.png")));

    public static TextureRegion makeShape(AbstractZone zone, ArrayList<ArrayList<MapRoomNode>> map, List<MapRoomNode> nodes, SpriteBatch sb) {
        int zoneWidth = (int) ((zone.getWidth() + 1) * SPACING_X) + FB_MARGIN;
        int zoneHeight = (int) ((zone.getHeight() + 1) * Settings.MAP_DST_Y) + FB_MARGIN;
        int zX = zone.getX(), zY = zone.getY();
        zone.hitboxes = new ArrayList<>();
        zone.hitboxRelativePositions = new HashMap<>();
        if (zone.zoneFb == null) {
            zone.zoneFb = new FrameBuffer(Pixmap.Format.RGBA8888, zoneWidth, zoneHeight, false);
        }

        if (zoneWidth > commonBuffer.getWidth() || zoneHeight > commonBuffer.getHeight()) {
            zoneWidth = Math.max(commonBuffer.getWidth(), zoneWidth);
            zoneHeight = Math.max(commonBuffer.getHeight(), zoneHeight);
            commonBuffer.dispose();
            commonBuffer = new FrameBuffer(Pixmap.Format.RGBA8888,
                    zoneWidth, zoneHeight, false);
        }

        tempMatrix.set(sb.getProjectionMatrix());

        sb.setColor(Color.WHITE);

        //draw base circles
        fbProjection.setToOrtho2D(0, 0, commonBuffer.getWidth(), commonBuffer.getHeight());
        sb.setProjectionMatrix(fbProjection);
        commonBuffer.begin();

        Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glColorMask(true, true, true, true);

        HashMap<MapRoomNode, Float> circleScales = new HashMap<>();

        //main circles
        for (MapRoomNode n : nodes) {
            float d = getClosestNodeDistance(n, map, (no) -> !nodes.contains(no));
            float circleScale = Math.min((d / MAX_NODE_SPACING) * CIRCLE_SIZE_MULT, MAX_CIRCLE_SCALE * CIRCLE_SIZE_MULT);
            circleScales.put(n, circleScale);
            float cX = (n.x - zX) * SPACING_X + FB_OFFSET + n.offsetX;
            float cY = (n.y - zY) * Settings.MAP_DST_Y + FB_OFFSET + n.offsetY;

            drawCircle(sb, cX, cY, circleScale);
            addHitbox(zone, cX, cY, circleScale);
        }

        //in-between circles. Each node in the zone looks to add a circle between it and nodes from the same zone if they are adjacent
        //(to the right and up to avoid drawing the same thing multiple times)
        for (MapRoomNode n : nodes) {
            for (MapRoomNode m : nodes) {
                boolean isMAboveAndClose = (m.y == n.y+1) && (Math.abs(m.x - n.x) <= 1);
                boolean isMToTheRight = m.y == n.y && m.x == n.x+1;
                if (isMAboveAndClose || isMToTheRight) {
                    float nX = (n.x - zX) * SPACING_X + FB_OFFSET + n.offsetX;
                    float mX = (m.x - zX) * SPACING_X + FB_OFFSET + m.offsetX;
                    float nY = (n.y - zY) * Settings.MAP_DST_Y + FB_OFFSET + n.offsetY;
                    float mY = (m.y - zY) * Settings.MAP_DST_Y + FB_OFFSET + m.offsetY;

                    for (int i = 1; i < 4; ++i) { //1-3
                        float circleScale = Interpolation.linear.apply(circleScales.get(n), circleScales.get(m), i / 4f);
                        float cX = Interpolation.linear.apply(nX, mX, i / 4f);
                        float cY = Interpolation.linear.apply(nY, mY, i / 4f);

                        drawCircle(sb, cX, cY, circleScale);
                        addHitbox(zone, cX, cY, circleScale);
                    }
                }
            }
        }

        sb.flush();
        commonBuffer.end();

        TextureRegion resultRegion = new TextureRegion(commonBuffer.getColorBufferTexture());
        resultRegion.flip(false, true);

        //start making the actual shape
        sb.setShader(shader);


        //first shader step (high range and rounding, low quality)
        shader.setUniformf("sizeX", zone.zoneFb.getWidth());
        shader.setUniformf("sizeY", zone.zoneFb.getHeight());
        shader.setUniformf("stepSize", FIRST_PASS_STEPSIZE); //1 = max quality, x1 range, 2 = lower quality, x2 range
        shader.setUniformf("subDiv", FIRST_PASS_SUBDIV); //= range when multiplied by stepSize, lowers performance quadratically
        shader.setUniformf("thresholdDivider", FIRST_PASS_DIVIDER); //higher = more range, less effective at rounding concave angles (like when 2 circles meet)
        shader.setUniformf("whitening", FIRST_PASS_WHITENING);
        shader.setUniformf("darkening", FIRST_PASS_DARKENING);

        fbProjection.setToOrtho2D(0, 0, zone.zoneFb.getWidth(), zone.zoneFb.getHeight());
        sb.setProjectionMatrix(fbProjection);
        zone.zoneFb.begin();
        Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glColorMask(true, true, true, true);

        sb.draw(resultRegion, 0, 0);
        sb.flush();
        zone.zoneFb.end();

        resultRegion.setRegion(zone.zoneFb.getColorBufferTexture());
        resultRegion.flip(false, true);

        //second inflation with low rounding
        shader.setUniformf("sizeX", commonBuffer.getWidth());
        shader.setUniformf("sizeY", commonBuffer.getHeight());
        shader.setUniformf("stepSize", SECOND_PASS_STEPSIZE);
        shader.setUniformf("subDiv", SECOND_PASS_SUBDIV);
        shader.setUniformf("thresholdDivider", SECOND_PASS_DIVIDER);
        shader.setUniformf("whitening", SECOND_PASS_WHITENING);
        shader.setUniformf("darkening", SECOND_PASS_DARKENING);

        fbProjection.setToOrtho2D(0, 0, commonBuffer.getWidth(), commonBuffer.getHeight());
        sb.setProjectionMatrix(fbProjection);
        commonBuffer.begin();
        sb.draw(resultRegion, 0, 0);
        sb.flush();
        commonBuffer.end();

        resultRegion.setRegion(commonBuffer.getColorBufferTexture());
        resultRegion.flip(false, true);

        //smoothing step (low range, high quality)
        shader.setUniformf("sizeX", zone.zoneFb.getWidth());
        shader.setUniformf("sizeY", zone.zoneFb.getHeight());
        shader.setUniformf("stepSize", SMOOTHING_STEPSIZE);
        shader.setUniformf("subDiv", SMOOTHING_SUBDIV);
        shader.setUniformf("thresholdDivider", SMOOTHING_DIVIDER);
        shader.setUniformf("whitening", SMOOTHING_WHITENING);
        shader.setUniformf("darkening", SMOOTHING_DARKENING);

        fbProjection.setToOrtho2D(0, 0, zone.zoneFb.getWidth(), zone.zoneFb.getHeight());
        sb.setProjectionMatrix(fbProjection);
        zone.zoneFb.begin();
        sb.draw(resultRegion, 0, 0);
        sb.flush();
        zone.zoneFb.end();

        resultRegion.setRegion(zone.zoneFb.getColorBufferTexture());
        resultRegion.flip(false, true);

        sb.setProjectionMatrix(tempMatrix); //Reset projection matrix
        sb.setShader(null);
        return resultRegion;
    }

    private static void drawCircle(SpriteBatch sb, float cX, float cY, float scale) {
        sb.draw(CIRCLE,
                cX - (CIRCLE.getRegionWidth() * 0.5f),
                cY - (CIRCLE.getRegionHeight() * 0.5f),
                CIRCLE.getRegionWidth() / 2f,
                CIRCLE.getRegionHeight() / 2f,
                CIRCLE.getRegionWidth(),
                CIRCLE.getRegionHeight(),
                Settings.scale * scale,
                Settings.scale * scale,
                0f);
    }

    private static void addHitbox(AbstractZone z, float cX, float cY, float scale) {
        Hitbox hb = new Hitbox(300f * scale, 300 * scale);
        z.hitboxes.add(hb);
        z.hitboxRelativePositions.put(hb, new Pair<>(cX,cY));
    }


    public static float getClosestNodeDistance(MapRoomNode center, ArrayList<ArrayList<MapRoomNode>> map, Predicate<MapRoomNode> filter) {
        int centerFloor = -1;
        int centerIndex = -1;
        for (ArrayList<MapRoomNode> floor : map) {
            if (floor.contains(center)) {
                centerFloor = map.indexOf(floor);
                centerIndex = floor.indexOf(center);
                break;
            }
        }

        float minDistance = MAX_NODE_SPACING, testDist = minDistance * minDistance;
        MapRoomNode closestNode = null;

        //check floor before
        if (centerFloor != 0) {
            for (MapRoomNode n : map.get(centerFloor -1)) {
                if (filter.test(n)) {
                    float d = nodeDistanceSquared(center, n);
                    if (d < testDist) {
                        testDist = d;
                        closestNode = n;
                    }
                }
            }
        }
        //check floor after
        if (centerFloor < map.size() - 2) {
            for (MapRoomNode n : map.get(centerFloor + 1)) {
                if (filter.test(n)) {
                    float d = nodeDistanceSquared(center, n);
                    if (d < testDist) {
                        testDist = d;
                        closestNode = n;
                    }
                }
            }
        }
        //check left and right nodes
        ArrayList<MapRoomNode> floor = map.get(centerFloor);
        if (centerIndex < floor.size() - 2) {
            if (filter.test(floor.get(centerIndex + 1))) {
                float d = nodeDistanceSquared(center, floor.get(centerIndex + 1));
                if (d < testDist) {
                    testDist = d;
                    closestNode = floor.get(centerIndex + 1);
                }
            }
        }
        if (centerIndex > 0) {
            if (filter.test(floor.get(centerIndex - 1))) {
                float d = nodeDistanceSquared(center, floor.get(centerIndex - 1));
                if (d < testDist) {
                    testDist = d;
                    closestNode = floor.get(centerIndex - 1);
                }
            }
        }

        if (closestNode != null) {
            float d = nodeDistance(center, closestNode);
            if (d < minDistance) return d;
        }

        return minDistance;
    }

    public static float nodeDistance(MapRoomNode n1, MapRoomNode n2) {
        return (float) Math.sqrt((n1.hb.cX - n2.hb.cX)*(n1.hb.cX - n2.hb.cX) + (n1.hb.cY - n2.hb.cY)*(n1.hb.cY - n2.hb.cY));
    }
    public static float nodeDistanceSquared(MapRoomNode n1, MapRoomNode n2) {
        return (n1.hb.cX - n2.hb.cX)*(n1.hb.cX - n2.hb.cX) + (n1.hb.cY - n2.hb.cY)*(n1.hb.cY - n2.hb.cY);
    }


    //imgui menu
    private final ImFloat imFloat = new ImFloat();

    public void makeImgui() {
        ImVec2 wPos = ImGui.getMainViewport().getPos();
        ImGui.setNextWindowPos(wPos.x + 50.0F, wPos.y + 70.0F, 4);
        ImGui.setNextWindowSize(465.0F, 465.0F, 4);
        if (ImGui.begin("Zone Shapes")) {
            imFloat.set(MAX_NODE_SPACING);
            ImGui.sliderFloat("Max node spacing", imFloat.getData(),25f,600f);
            if (imFloat.get() != MAX_NODE_SPACING) {
                MAX_NODE_SPACING = imFloat.get() * Settings.scale;
            }
            ImGui.separator();
            imFloat.set(CIRCLE_SIZE_MULT);
            ImGui.sliderFloat("Circle Size Mult", imFloat.getData(),0.1f,3f);
            if (imFloat.get() != CIRCLE_SIZE_MULT) {
                CIRCLE_SIZE_MULT = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(MAX_CIRCLE_SCALE);
            ImGui.sliderFloat("Max Circle Scale", imFloat.getData(),1f,5f);
            if (imFloat.get() != MAX_CIRCLE_SCALE) {
                MAX_CIRCLE_SCALE = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(FIRST_PASS_STEPSIZE);
            ImGui.sliderFloat("firstPassStep", imFloat.getData(),1f,15f);
            if (imFloat.get() != FIRST_PASS_STEPSIZE) {
                FIRST_PASS_STEPSIZE = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(FIRST_PASS_SUBDIV);
            ImGui.sliderFloat("firstPassSubdiv", imFloat.getData(),1f,10f);
            if (imFloat.get() != FIRST_PASS_SUBDIV) {
                FIRST_PASS_SUBDIV = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(FIRST_PASS_DIVIDER);
            ImGui.sliderFloat("firstPassDivider", imFloat.getData(),0.5f,3f);
            if (imFloat.get() != FIRST_PASS_DIVIDER) {
                FIRST_PASS_DIVIDER = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(SECOND_PASS_STEPSIZE);
            ImGui.sliderFloat("secondPassStep", imFloat.getData(),1f,15f);
            if (imFloat.get() != SECOND_PASS_STEPSIZE) {
                SECOND_PASS_STEPSIZE = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(SECOND_PASS_SUBDIV);
            ImGui.sliderFloat("secondPassSubdiv", imFloat.getData(),1f,10f);
            if (imFloat.get() != SECOND_PASS_SUBDIV) {
                SECOND_PASS_SUBDIV = imFloat.get();
            }
            ImGui.separator();
            imFloat.set(SECOND_PASS_DIVIDER);
            ImGui.sliderFloat("secondPassDivider", imFloat.getData(),0.5f,3f);
            if (imFloat.get() != SECOND_PASS_DIVIDER) {
                SECOND_PASS_DIVIDER = imFloat.get();
            }
            ImGui.separator();
            if (ImGui.button("Remake Shapes")) {
                for (AbstractZone z : BetterMapGenerator.getActiveZones(AbstractDungeon.map)) {
                    z.dispose();
                    z.zoneFb = null;
                }
            }
        }
        ImGui.end();
    }

    public void receiveImGui() {
        if (AbstractDungeon.isPlayerInDungeon()) makeImgui();
    }
}
