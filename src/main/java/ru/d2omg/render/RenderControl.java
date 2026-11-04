package ru.d2omg.render;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Only serializes bounded render requests; never runs a shell or renderer in Minecraft. */
public final class RenderControl implements ModInitializer {
    static final Path ROOT=Path.of(System.getProperty("d2render.queue", "config/d2gate/render-queue"));
    private static final ScheduledExecutorService IO=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"d2render-control");thread.setDaemon(true);return thread;
    });
    private static volatile String cachedStatus="Очередь рендера: загружаю состояние рабочего процесса NAS…";
    private static final Deque<String> notices=new ArrayDeque<>();
    private static final String HELP="Рендер Main: /render here | area x1 z1 x2 z2 [y] | camera x y z tx ty tz | video x1 y1 z1 x2 y2 z2 tx ty tz seconds | status | cancel ID. Область до 192×192, видео 2–8 сек.";

    @Override public void onInitialize() {
        IO.scheduleWithFixedDelay(() -> {
            try {cachedStatus=status()+(notices.isEmpty()?"":"\nПоследние запросы:\n"+String.join("\n",notices));}
            catch(Exception error) {cachedStatus="Не удалось прочитать очередь рендера NAS.";}
        },0,2,TimeUnit.SECONDS);
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment) -> {
            var root=Commands.literal("render").requires(RenderControl::allowed)
                .executes(ctx -> reply(ctx.getSource(),HELP));
            for(String action:List.of("area","camera","video","cancel")) {
                root.then(Commands.literal(action)
                    .then(Commands.argument("parameters",StringArgumentType.greedyString())
                    .executes(ctx -> submit(ctx.getSource(),action,StringArgumentType.getString(ctx,"parameters")))));
            }
            root.then(Commands.literal("here").requires(s -> s.isPlayer() && allowed(s))
                .executes(ctx -> {
                    var player=ctx.getSource().getPlayerOrException();
                    Vec3 p=player.getEyePosition(),t=p.add(player.getViewVector(1).scale(64));
                    return submit(ctx.getSource(),"camera",p.x+" "+p.y+" "+p.z+" "+t.x+" "+t.y+" "+t.z);
                }));
            root.then(Commands.literal("status").executes(ctx -> submit(ctx.getSource(),"status","")));
            dispatcher.register(root);
        });
    }

    static boolean allowed(CommandSourceStack source) {
        if(source.isPlayer()) {
            var player=source.getPlayer();
            return player!=null && source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
        }
        return source.getEntity()==null && source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.OWNERS));
    }

    static int reply(CommandSourceStack source,String text) {
        source.sendSuccess(() -> Component.literal(text),false);return 1;
    }

    private static int submit(CommandSourceStack source,String action,String parameters) {
        if(!allowed(source))return 0;
        if(action.equals("status"))return reply(source,cachedStatus);
        if(!action.equals("status") && !action.equals("cancel") && source.isPlayer()
                && !source.getLevel().dimension().equals(Level.OVERWORLD)) {
            return reply(source,"Рендер пока доступен только для обычного мира Main.");
        }
        final JsonObject request;
        try { request=List.of("status","cancel").contains(action)?null:parse(action,parameters); }
        catch(IllegalArgumentException error) {return reply(source,error.getMessage());}
        if(action.equals("cancel") && !parameters.matches("[a-zA-Z0-9_-]{8,80}"))return reply(source,"Укажи полный ID из /render status.");
        String requestId=String.format(Locale.ROOT,"%013d-%s",Instant.now().toEpochMilli(),UUID.randomUUID().toString().substring(0,8));
        String actor=source.getTextName();
        reply(source,action.equals("cancel")?"Отмена запрошена: "+parameters:
                "Запрос рендера: "+requestId+". Подтверждение и позиция — /render status.");
        IO.execute(() -> {
            String result;
            try {
                if(action.equals("status"))result=status();
                else if(action.equals("cancel")) {
                    Path input=ROOT.resolve("inbox").resolve(parameters+".json");
                    if(!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Задание не найдено.");
                    Path previous=ROOT.resolve("results").resolve(parameters+".json");
                    if(Files.exists(previous) && terminal(read(previous)))throw new IllegalArgumentException("Задание уже завершено.");
                    Files.createDirectories(ROOT.resolve("cancel"));
                    Files.writeString(ROOT.resolve("cancel").resolve(parameters),"cancel",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
                    result="Отмена запрошена: "+parameters+". Во время рендера проверка каждые 15 секунд; сборка/отправка могут закончить текущий этап.";
                } else {
                    Files.createDirectories(ROOT.resolve("inbox"));
                    int ahead=pending().size();
                    if(ahead>=20)throw new IllegalArgumentException("Очередь заполнена: максимум 20 заданий. Дождись завершения или отмени ненужные.");
                    String id=requestId;
                    request.addProperty("requested_by",actor);
                    Path temp=ROOT.resolve("inbox").resolve(id+".pending");
                    Files.writeString(temp,request.toString(),StandardOpenOption.CREATE_NEW);
                    Files.move(temp,ROOT.resolve("inbox").resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);
                    result="В очереди: "+id+". Незавершённых заданий перед ним: "+ahead+". /render status — прогресс. Результат придёт в закрытый канал рендеров.";
                }
            } catch(Exception error) {
                result=requestId+": "+(error instanceof IllegalArgumentException?error.getMessage():"Не удалось обратиться к очереди рендера. Проверь рабочий процесс NAS.");
            }
            String message=result;
            notices.addLast(message);
            while(notices.size()>5)notices.removeFirst();
            source.getServer().execute(() -> reply(source,message));
        });
        return 1;
    }

    static JsonObject parse(String kind,String parameters) {
        if(!List.of("area", "camera", "video").contains(kind))throw new IllegalArgumentException("Unknown render request kind.");
        String[] values=parameters.trim().split("\\s+");
        int count=kind.equals("area")?4:kind.equals("camera")?6:10;
        if(values.length!=count && !(kind.equals("area") && values.length==5))throw new IllegalArgumentException(HELP);
        double[] nums=new double[values.length];
        try {
            for(int i=0;i<values.length;i++) {
                nums[i]=Double.parseDouble(values[i]);
                if(!Double.isFinite(nums[i]) || Math.abs(nums[i])>29999000)throw new NumberFormatException();
            }
        } catch(NumberFormatException error) {throw new IllegalArgumentException("Нужны конечные числовые координаты в пределах мира.");}
        JsonObject request=new JsonObject();request.addProperty("kind",kind);
        if(kind.equals("area")) {
            double y=values.length==5?nums[4]:80;
            if(y< -64 || y>320 || Math.max(Math.abs(nums[0]-nums[2]),Math.abs(nums[1]-nums[3]))>192)
                throw new IllegalArgumentException("Область до 192×192 блоков; высота цели от -64 до 320.");
            request.add("a",vec(nums[0],y,nums[1]));request.add("b",vec(nums[2],y,nums[3]));
        } else {
            request.add("camera",vec(nums[0],nums[1],nums[2]));
            int t=kind.equals("video")?6:3;
            request.add("target",vec(nums[t],nums[t+1],nums[t+2]));
            if(kind.equals("video")) {
                request.add("end",vec(nums[3],nums[4],nums[5]));
                if(nums[9]!=Math.rint(nums[9]) || nums[9]<2 || nums[9]>8)throw new IllegalArgumentException("Длительность видео — целое число от 2 до 8 секунд.");
                request.addProperty("seconds",(int)nums[9]);
            }
            for(int offset:kind.equals("video")?new int[]{0,3}:new int[]{0}) {
                double distance=0;
                for(int axis=0;axis<3;axis++)distance+=Math.pow(nums[offset+axis]-nums[t+axis],2);
                if(distance<4 || distance>240*240)throw new IllegalArgumentException("Камера должна быть в 2–240 блоках от цели.");
            }
            for(int offset=1;offset<(kind.equals("video")?9:6);offset+=3)
                if(nums[offset]< -64 || nums[offset]>512)throw new IllegalArgumentException("Высота камеры/цели от -64 до 512.");
        }
        return request;
    }

    private static JsonArray vec(double x,double y,double z) {JsonArray v=new JsonArray();v.add(x);v.add(y);v.add(z);return v;}
    private static JsonObject read(Path path) throws Exception {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>32768)throw new IllegalArgumentException("Некорректный файл очереди.");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static boolean terminal(JsonObject object) {return List.of("success","cancelled","error").contains(object.get("stage").getAsString());}
    private static List<Path> pending() throws Exception {
        List<Path> result=new ArrayList<>();
        if(!Files.isDirectory(ROOT.resolve("inbox")))return result;
        try(var files=Files.list(ROOT.resolve("inbox"))) {
            for(Path path:files.filter(p -> fileName(p).matches("[a-zA-Z0-9_-]{8,80}\\.json"))
                    .sorted(Comparator.comparingLong(RenderControl::createdAt).thenComparing(Path::toString)).toList()) {
                Path output=ROOT.resolve("results").resolve(fileName(path));
                if(!Files.exists(output) || !terminal(read(output)))result.add(path);
            }
        }
        return result;
    }
    private static long createdAt(Path path) {
        try {return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis();}
        catch(Exception error) {return Long.MAX_VALUE;}
    }
    private static String status() throws Exception {
        StringBuilder text=new StringBuilder("Очередь рендера Main");
        Path status=ROOT.resolve("status.json");
        if(Files.exists(status)) {
            JsonObject data=read(status);
            text.append("\nОбновлено: ").append(data.get("updated").getAsString());
            if(data.has("active") && data.get("active").isJsonObject()) {
                JsonObject active=data.getAsJsonObject("active");
                text.append("\nСейчас: ").append(label(active));
            }
            if(data.has("recent"))for(JsonElement entry:data.getAsJsonArray("recent")) {
                JsonObject item=entry.getAsJsonObject();text.append("\n").append(item.get("id").getAsString()).append(": ").append(label(item));
                if(item.has("url"))text.append(" ").append(item.get("url").getAsString());
                if(item.has("error"))text.append(" — ").append(item.get("error").getAsString());
            }
        } else text.append("\nРабочий процесс ещё не сообщил статус.");
        int position=0;
        for(Path path:pending())text.append("\n").append(++position).append(". ").append(fileName(path).replace(".json",""));
        if(position==0)text.append("\nОжидающих заданий нет.");
        return text.toString();
    }
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
    private static String label(JsonObject value) {
        String stage=value.has("stage")?value.get("stage").getAsString():"queued";
        String name=switch(stage) {
            case "success" -> "готово"; case "error" -> "ошибка"; case "cancelled" -> "отменено";
            case "awaiting_delivery" -> "готово, ожидает отправки"; case "render","running" -> "рендер";
            case "prepare","copying","starting" -> "подготовка"; case "encoding" -> "сборка видео";
            case "selecting" -> "выбор участка"; case "sending" -> "отправка";
            case "paused_for_load","waiting_for_resources" -> "ожидание свободных ресурсов";
            case "idle" -> "свободно"; default -> "ожидание";
        };
        if(value.has("id"))name+=" "+value.get("id").getAsString();
        if(value.has("frame"))name+=" · кадр "+value.get("frame")+"/"+value.get("frames");
        if(value.has("spp") && !value.get("spp").isJsonNull())name+=" · "+value.get("spp")+" SPP";
        return name;
    }
}
