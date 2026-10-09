package ru.d2omg.render;

import org.junit.jupiter.api.Test;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class RenderControlTest {
    @Test void consoleRequiresOwnerPermissions() {
        for(var permissions:new LevelBasedPermissionSet[]{
                LevelBasedPermissionSet.ALL,
                LevelBasedPermissionSet.GAMEMASTER,
                LevelBasedPermissionSet.ADMIN,
                LevelBasedPermissionSet.OWNER}) {
            var source=new CommandSourceStack(CommandSource.NULL, Vec3.ZERO,
                Vec2.ZERO,null,permissions,Component.literal("Rcon"),null);
            assertEquals(permissions==LevelBasedPermissionSet.OWNER,RenderControl.allowed(source));
        }
    }
    @Test void acceptsAreaAndPreservesHeight() {
        var request=RenderControl.parse("area","-50 -80 50 80 100");
        assertEquals(100,request.getAsJsonArray("a").get(1).getAsInt());
    }
    @Test void acceptsVideoEndpointsAndDuration() {
        var request=RenderControl.parse("video","0 100 0 100 100 0 50 70 80 2");
        assertEquals(2,request.get("seconds").getAsInt());
        assertEquals(100,request.getAsJsonArray("end").get(0).getAsInt());
    }
    @Test void rejectsHugeArea() {assertThrows(IllegalArgumentException.class,()->RenderControl.parse("area","0 0 500 500"));}
    @Test void rejectsNonfiniteCoordinate() {assertThrows(IllegalArgumentException.class,()->RenderControl.parse("camera","NaN 100 0 0 70 0"));}
    @Test void rejectsShellText() {assertThrows(IllegalArgumentException.class,()->RenderControl.parse("area","0 0 1 1;touch /tmp/a"));}
    @Test void rejectsCameraAtTarget() {assertThrows(IllegalArgumentException.class,()->RenderControl.parse("camera","0 70 0 0 70 0"));}
    @Test void rejectsLongOrFractionalVideo() {
        for(String duration:new String[]{"100","2.5","-1"})assertThrows(IllegalArgumentException.class,
            ()->RenderControl.parse("video","0 100 0 100 100 0 50 70 80 "+duration));
    }
}
