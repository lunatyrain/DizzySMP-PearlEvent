package dev.dizzysmp.pearlevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PearlEventPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PERM = "infpearlevent.admin";
    private static final Color PURPLE = Color.fromRGB(0x9B, 0x5D, 0xE5);
    private static final Color CYAN = Color.fromRGB(0x00, 0xF5, 0xD4);
    private static final int LAUNCH_TICKS = 30;

    private Plugin dizzy;
    private Method createPearl;

    private BukkitTask countdown;
    private BukkitTask sequence;
    private int remaining;
    private Location activeSpot;
    private int ticketCx, ticketCz;
    private World ticketWorld;
    private ItemDisplay rocketModel;
    private TextDisplay timerText;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dizzy = getServer().getPluginManager().getPlugin("DizzySMP");
        try {
            createPearl = dizzy.getClass().getMethod("createInfinitePearl");
        } catch (Exception e) {
            getLogger().severe("Could not find DizzySMP#createInfinitePearl - is DizzySMP 1.0.0 installed?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var cmd = getCommand("infpearlevent");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        cancelAll();
    }

    // ------------------------------------------------------------ helpers

    private static Component c(String mini) {
        return MM.deserialize(mini);
    }

    private void msg(CommandSender s, String mini) {
        s.sendMessage(c(mini));
    }

    private boolean isRunning() {
        return countdown != null || sequence != null;
    }

    private Location eventSpot() {
        if (!getConfig().getBoolean("event.pos-set")) return null;
        World w = Bukkit.getWorld(getConfig().getString("event.world", "world"));
        if (w == null) return null;
        return new Location(w, getConfig().getInt("event.x") + 0.5, getConfig().getInt("event.y"),
                getConfig().getInt("event.z") + 0.5);
    }

    private static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private static String fmt(int secs) {
        int h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    /** Parses 90, 90s, 5m, 1h30m, 1d. Returns -1 if invalid. */
    private static long parseDuration(String in) {
        String s = in.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) return -1;
        long total = 0;
        int i = 0;
        while (i < s.length()) {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (start == i || i - start > 9) return -1;
            long n = Long.parseLong(s.substring(start, i));
            long mult = 1;
            if (i < s.length()) {
                switch (s.charAt(i)) {
                    case 's' -> mult = 1;
                    case 'm' -> mult = 60;
                    case 'h' -> mult = 3600;
                    case 'd' -> mult = 86400;
                    default -> { return -1; }
                }
                i++;
            }
            total += n * mult;
        }
        return total;
    }

    /** Height of the countdown text above the event position (blocks). */
    private static final double TEXT_HEIGHT = 4.6;

    private void spawnDisplays(Location spot) {
        removeDisplays();
        World w = spot.getWorld();
        timerText = w.spawn(spot.clone().add(0, TEXT_HEIGHT, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setViewRange(4f);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(2f, 2f, 2f), new org.joml.Quaternionf()));
            d.setPersistent(false);
            d.text(c("<gradient:#9B5DE5:#00F5D4><bold>Inf Pearl Event</bold></gradient>"));
        });
    }

    /** The rocket only appears once it actually fires. */
    private void spawnRocket(Location at) {
        if (rocketModel != null) rocketModel.remove();
        rocketModel = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
            var rm = rocket.getItemMeta();
            // pearl-coloured rocket texture from the DizzySMP resource pack
            rm.setItemModel(new org.bukkit.NamespacedKey("lunasitems", "pearl_rocket"));
            rocket.setItemMeta(rm);
            d.setItemStack(rocket);
            d.setBillboard(Display.Billboard.CENTER);
            d.setViewRange(4f);
            d.setTeleportDuration(1);
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(1f, 1f, 1f), new org.joml.Quaternionf()));
            d.setPersistent(false);
        });
    }

    private void setTimerText(String mini) {
        if (timerText != null && timerText.isValid()) timerText.text(c(mini));
    }

    private void moveDisplays(Location rocket) {
        if (rocketModel != null && rocketModel.isValid()) rocketModel.teleport(rocket);
    }

    private void removeDisplays() {
        if (rocketModel != null) { rocketModel.remove(); rocketModel = null; }
        if (timerText != null) { timerText.remove(); timerText = null; }
    }

    private static String countdownText(int secs) {
        return "<gradient:#9B5DE5:#00F5D4><bold>Inf Pearl Event</bold></gradient><newline><white><bold>" + fmt(secs);
    }

    private void cancelAll() {
        removeDisplays();
        if (countdown != null) { countdown.cancel(); countdown = null; }
        if (sequence != null) { sequence.cancel(); sequence = null; }
        releaseTicket();
        activeSpot = null;
    }

    private void takeTicket(Location l) {
        releaseTicket();
        ticketWorld = l.getWorld();
        ticketCx = l.getBlockX() >> 4;
        ticketCz = l.getBlockZ() >> 4;
        ticketWorld.addPluginChunkTicket(ticketCx, ticketCz, this);
    }

    private void releaseTicket() {
        if (ticketWorld != null) {
            ticketWorld.removePluginChunkTicket(ticketCx, ticketCz, this);
            ticketWorld = null;
        }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            msg(sender, "<red>Only operators can use this command.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setpos" -> setPos(sender, args);
            case "settimer" -> setTimer(sender, args);
            case "start" -> start(sender);
            case "stop" -> {
                if (!isRunning()) {
                    msg(sender, "<red>No Infinite Pearl event is running.");
                } else {
                    cancelAll();
                    msg(sender, "<yellow>Infinite Pearl event cancelled.");
                    Bukkit.broadcast(c("<gradient:#9B5DE5:#00F5D4>Inf Pearl Event cancelled.</gradient>"));
                }
            }
            case "status" -> status(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        msg(s, "<gradient:#9B5DE5:#00F5D4><bold>Infinite Pearl Event</bold></gradient>");
        msg(s, "<yellow>/" + label + " setpos [x y z] [world]<gray> - set the event position (default: where you stand)");
        msg(s, "<yellow>/" + label + " settimer <time><gray> - countdown length, e.g. 90, 30s, 10m, 1h30m");
        msg(s, "<yellow>/" + label + " start<gray> - start the countdown and announce the coordinates");
        msg(s, "<yellow>/" + label + " stop<gray> - cancel a running event");
        msg(s, "<yellow>/" + label + " status<gray> - show position, timer and state");
    }

    private void status(CommandSender s) {
        Location l = eventSpot();
        msg(s, "<aqua>Position: <white>" + (l == null ? "not set" : coords(l) + " (" + l.getWorld().getName() + ")"));
        msg(s, "<aqua>Timer: <white>" + fmt(getConfig().getInt("timer-seconds", 600)));
        msg(s, "<aqua>State: <white>" + (countdown != null ? "counting down, " + fmt(remaining) + " left"
                : sequence != null ? "firework in progress" : "idle"));
    }

    private void setPos(CommandSender sender, String[] args) {
        World world;
        int x, y, z;
        if (args.length == 1) {
            if (!(sender instanceof Player p)) {
                msg(sender, "<red>Console must use: setpos <x> <y> <z> <world>");
                return;
            }
            Location l = p.getLocation();
            world = l.getWorld();
            x = l.getBlockX(); y = l.getBlockY(); z = l.getBlockZ();
        } else if (args.length >= 4) {
            Location base = sender instanceof Player p ? p.getLocation() : null;
            try {
                x = coord(args[1], base == null ? 0 : base.getBlockX(), base != null);
                y = coord(args[2], base == null ? 0 : base.getBlockY(), base != null);
                z = coord(args[3], base == null ? 0 : base.getBlockZ(), base != null);
            } catch (IllegalArgumentException e) {
                msg(sender, "<red>Invalid coordinates. Usage: setpos <x> <y> <z> [world]");
                return;
            }
            if (args.length >= 5) {
                world = Bukkit.getWorld(args[4]);
                if (world == null) { msg(sender, "<red>Unknown world: " + args[4]); return; }
            } else if (base != null) {
                world = base.getWorld();
            } else {
                msg(sender, "<red>Console must give a world: setpos <x> <y> <z> <world>");
                return;
            }
        } else {
            msg(sender, "<red>Usage: /infpearlevent setpos [x y z] [world]");
            return;
        }
        getConfig().set("event.world", world.getName());
        getConfig().set("event.x", x);
        getConfig().set("event.y", y);
        getConfig().set("event.z", z);
        getConfig().set("event.pos-set", true);
        saveConfig();
        msg(sender, "<green>Event position set to <white>" + x + ", " + y + ", " + z + "<green> in " + world.getName() + ".");
    }

    private static int coord(String s, int base, boolean relativeOk) {
        if (s.startsWith("~")) {
            if (!relativeOk) throw new IllegalArgumentException();
            return base + (s.length() == 1 ? 0 : (int) Math.floor(Double.parseDouble(s.substring(1))));
        }
        return (int) Math.floor(Double.parseDouble(s));
    }

    private void setTimer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "<red>Usage: /infpearlevent settimer <time>  (e.g. 90, 30s, 10m, 1h30m)");
            return;
        }
        long secs = parseDuration(args[1]);
        if (secs < 1 || secs > 7L * 86400) {
            msg(sender, "<red>Invalid time. Use 1 second to 7 days, e.g. 90, 30s, 10m, 1h30m.");
            return;
        }
        getConfig().set("timer-seconds", (int) secs);
        saveConfig();
        msg(sender, "<green>Countdown set to <white>" + fmt((int) secs) + "<green>.");
    }

    private void start(CommandSender sender) {
        if (isRunning()) {
            msg(sender, "<red>An Infinite Pearl event is already running. Use /infpearlevent stop first.");
            return;
        }
        Location spot = eventSpot();
        if (spot == null) {
            msg(sender, "<red>Set the event position first: /infpearlevent setpos");
            return;
        }
        activeSpot = spot.clone();
        takeTicket(activeSpot);
        remaining = Math.max(1, getConfig().getInt("timer-seconds", 600));
        spawnDisplays(activeSpot);
        setTimerText(countdownText(remaining));

        Bukkit.broadcast(c("<gradient:#9B5DE5:#00F5D4><bold>Inf Pearl Event at: " + coords(activeSpot) + "</bold></gradient>"));
        Bukkit.broadcast(c("<gray>The Infinite Pearl drops in <white>" + fmt(remaining) + "<gray>."));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 1f, 1.2f);
        }

        countdown = new BukkitRunnable() {
            @Override
            public void run() {
                remaining--;
                if (remaining <= 0) {
                    countdown = null;
                    cancel();
                    launchSequence();
                    return;
                }
                setTimerText(countdownText(remaining));
                announce(remaining);
                if (remaining <= 3) countdownEffects(remaining);
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    /** Effects and sounds for the final 3 seconds (3, 2, 1). */
    private void countdownEffects(int left) {
        World w = activeSpot.getWorld();
        Location base = activeSpot.clone().add(0, 0.1, 0);
        Particle.DustTransition dt = new Particle.DustTransition(PURPLE, CYAN, 1.5f);
        double r = 0.8 + left * 0.9; // ring tightens as the launch gets closer
        for (int i = 0; i < 36; i++) {
            double a = 2 * Math.PI * i / 36;
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, base.getX() + Math.cos(a) * r, base.getY(),
                    base.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, dt);
        }
        w.spawnParticle(Particle.END_ROD, base.clone().add(0, 1, 0), 12 + (3 - left) * 10, 0.25, 0.8, 0.25, 0.02);
        w.spawnParticle(Particle.FIREWORK, base, 6 + (3 - left) * 6, 0.3, 0.1, 0.3, 0.05);
        float pitch = left == 3 ? 0.8f : left == 2 ? 1.0f : 1.4f;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 1f, pitch);
        }
        w.playSound(base, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.MASTER, 4f, pitch);
    }

    /** Finds a standing spot at (x,z) close to refY: climbs out of blocks, drops down onto the floor. */
    private static Location groundAt(World w, int x, int refY, int z) {
        int y = refY;
        for (int i = 0; i < 8 && !w.getBlockAt(x, y, z).isPassable(); i++) y++;
        for (int i = 0; i < 8 && y - 1 > w.getMinHeight() && w.getBlockAt(x, y - 1, z).isPassable(); i++) y--;
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    private void announce(int left) {
        boolean chat = left == 300 || left == 120 || left == 60 || left == 30 || left == 10;
        if (chat) {
            Bukkit.broadcast(c("<gradient:#9B5DE5:#00F5D4>Inf Pearl Event at: " + coords(activeSpot)
                    + "</gradient> <gray>- <white>" + fmt(left) + "<gray> left"));
        }
    }

    // ------------------------------------------------------------ firework sequence

    private void launchSequence() {
        final Location base = activeSpot.clone();
        final World w = base.getWorld();
        final double height = Math.max(8, getConfig().getDouble("launch-height", 30));

        // Where the firework bursts: above the spot; if that is inside blocks use the surface instead.
        double burstY = base.getY() + height;
        Location probe = new Location(w, base.getX(), burstY, base.getZ());
        if (!probe.getBlock().isPassable()) {
            burstY = Math.max(burstY, w.getHighestBlockYAt(base.getBlockX(), base.getBlockZ()) + 12);
        }
        burstY = Math.min(burstY, w.getMaxHeight() - 3);
        final double finalBurstY = burstY;
        final Location burst = new Location(w, base.getX(), finalBurstY, base.getZ());

        spawnRocket(base.clone().add(0, 1.0, 0));
        if (timerText != null) { timerText.remove(); timerText = null; }
        w.playSound(base, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 6f, 0.7f);

        sequence = new BukkitRunnable() {
            int t = 0;
            Item pearl = null;
            Location target = null;
            final int ARC = 40;
            final Particle.DustTransition dust = new Particle.DustTransition(PURPLE, CYAN, 1.5f);

            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable ex) {
                    getLogger().severe("Pearl event failed: " + ex);
                    ex.printStackTrace();
                    sequence = null;
                    cancel();
                    removeDisplays();
                    releaseTicket();
                }
            }

            private void tick() {
                if (t < LAUNCH_TICKS) {
                    rise();
                } else if (t == LAUNCH_TICKS) {
                    burstNow();
                }
                if (t >= LAUNCH_TICKS) {
                    int bt = t - LAUNCH_TICKS;
                    if (bt > 0 && bt <= 14) sphere(burst, 5.5 * (1 - Math.pow(1 - bt / 14.0, 3)), 70);
                    if (pearl != null) followPearl();
                }
                t++;
            }

            private void rise() {
                double f = (t + 1) / (double) LAUNCH_TICKS;
                double y = base.getY() + (finalBurstY - base.getY()) * f * f;
                Location l = new Location(w, base.getX(), y, base.getZ());
                moveDisplays(l.clone().add(0, 1.0, 0));
                w.spawnParticle(Particle.FIREWORK, l, 4, 0.06, 0.06, 0.06, 0.02);
                w.spawnParticle(Particle.END_ROD, l, 2, 0.04, 0.2, 0.04, 0.01);
                // spiral of purple -> cyan around the rocket
                double a = t * 0.9;
                for (int k = 0; k < 2; k++) {
                    double aa = a + k * Math.PI;
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION,
                            l.getX() + Math.cos(aa) * 0.45, y - 0.3, l.getZ() + Math.sin(aa) * 0.45,
                            1, 0, 0, 0, 0, dust);
                }
                // faint pearl "launch pad" glow at the base
                if (t % 3 == 0) {
                    ring(base.clone().add(0, 0.1, 0), 1.2 + t * 0.03, 18);
                }
                if (t % 6 == 0) {
                    w.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 3f, (float) (0.9 + f * 0.5));
                }
            }

            private void burstNow() {
                removeDisplays();
                // real firework burst (sound + vanilla visuals), coloured like the Infinite Pearl
                Firework fw = w.spawn(burst, Firework.class, f -> {
                    FireworkMeta meta = f.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(PURPLE, CYAN)
                            .withFade(Color.WHITE, CYAN)
                            .withTrail().withFlicker().build());
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.STAR)
                            .withColor(CYAN).withFade(PURPLE).build());
                    f.setFireworkMeta(meta);
                    f.setSilent(false);
                });
                fw.detonate();

                w.playSound(burst, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.MASTER, 8f, 0.8f);
                w.playSound(burst, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.MASTER, 8f, 1f);
                w.playSound(burst, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 2f, 1.6f);

                // pearl-shaped core: a dense little sphere + equator rings, and rays flying outward
                sphere(burst, 0.9, 60);
                sphere(burst, 1.4, 60);
                for (int i = 0; i < 90; i++) {
                    Vector d = fib(i, 90).multiply(0.45);
                    w.spawnParticle(Particle.END_ROD, burst, 0, d.getX(), d.getY(), d.getZ(), 1.0);
                }

                // drop the pearl
                ItemStack stack;
                try {
                    stack = (ItemStack) createPearl.invoke(dizzy);
                } catch (Exception e) {
                    getLogger().severe("Could not create the Infinite Pearl: " + e);
                    stack = new ItemStack(org.bukkit.Material.ENDER_PEARL);
                }
                // pick a random landing spot inside the 9x9 area centred on the event position
                var rnd = java.util.concurrent.ThreadLocalRandom.current();
                target = groundAt(w, base.getBlockX() + rnd.nextInt(-4, 5), base.getBlockY(),
                        base.getBlockZ() + rnd.nextInt(-4, 5));
                pearl = w.dropItem(burst, stack, it -> {
                    it.setVelocity(new Vector(0, 0, 0));
                    it.setGravity(false); // flown along a scripted arc, gravity returns on landing
                    it.setGlowing(true);
                    it.setInvulnerable(true);
                    it.setUnlimitedLifetime(true);
                    it.setPickupDelay(40);
                });
            }

            private void followPearl() {
                if (pearl.isDead() || !pearl.isValid()) {
                    finish(pearl.getLocation(), false);
                    return;
                }
                int bt = t - LAUNCH_TICKS;
                if (bt <= ARC) {
                    double f = bt / (double) ARC;
                    double x = burst.getX() + (target.getX() - burst.getX()) * f;
                    double z = burst.getZ() + (target.getZ() - burst.getZ()) * f;
                    double y = burst.getY() + (target.getY() - burst.getY()) * f * f; // accelerates like a fall
                    Location pl = new Location(w, x, y, z);
                    pearl.teleport(pl);
                    pearl.setVelocity(new Vector(0, 0, 0));
                    w.spawnParticle(Particle.END_ROD, pl, 2, 0.1, 0.1, 0.1, 0.01);
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pl, 4, 0.15, 0.15, 0.15, 0, dust);
                } else {
                    pearl.teleport(target);
                    pearl.setGravity(true);
                    finish(target, true);
                }
            }

            private void finish(Location at, boolean landed) {
                cancel();
                sequence = null;
                if (landed) {
                    w.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.5, 0), 40, 0.4, 0.6, 0.4, 0.12);
                    ring(at.clone().add(0, 0.1, 0), 1.5, 30);
                    ring(at.clone().add(0, 0.1, 0), 2.5, 40);
                    w.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.MASTER, 4f, 1.4f);
                    w.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 3f, 0.8f);
                    Bukkit.broadcast(c("<gradient:#9B5DE5:#00F5D4><bold>The Infinite Pearl has landed!</bold></gradient>"));
                    pearl.setPickupDelay(0);
                }
                activeSpot = null;
                releaseTicket();
            }

            private void ring(Location center, double r, int n) {
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * i / n;
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION,
                            center.getX() + Math.cos(a) * r, center.getY(), center.getZ() + Math.sin(a) * r,
                            1, 0, 0, 0, 0, dust);
                }
            }

            private void sphere(Location center, double r, int n) {
                for (int i = 0; i < n; i++) {
                    Vector d = fib(i, n).multiply(r);
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION,
                            center.getX() + d.getX(), center.getY() + d.getY(), center.getZ() + d.getZ(),
                            1, 0, 0, 0, 0, dust);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** i-th of n evenly spread unit vectors on a sphere (Fibonacci lattice). */
    private static Vector fib(int i, int n) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        double y = 1 - 2.0 * (i + 0.5) / n;
        double rad = Math.sqrt(Math.max(0, 1 - y * y));
        double th = golden * i;
        return new Vector(Math.cos(th) * rad, y, Math.sin(th) * rad);
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;
        if (args.length == 1) {
            for (String s : List.of("setpos", "settimer", "start", "stop", "status", "help")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("settimer")) {
            out.addAll(List.of("30s", "5m", "10m", "30m", "1h"));
        } else if (args[0].equalsIgnoreCase("setpos") && args.length >= 2 && args.length <= 4) {
            out.add("~");
        } else if (args[0].equalsIgnoreCase("setpos") && args.length == 5) {
            for (World w : Bukkit.getWorlds()) out.add(w.getName());
        }
        return out;
    }
}
