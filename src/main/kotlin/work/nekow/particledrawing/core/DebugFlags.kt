package work.nekow.particledrawing.core

// 运行期的调试开关（与配置互补）：
// 配置是「启动时定下来的默认值」，这里是「跑起来之后现改」——排查问题时不必改配置文件重启。
// 只放与诊断输出有关、不影响任何播放语义的开关。

object DebugFlags {

    /**
     * 编排动画程序 arm 时是否打 INFO 日志（默认 false = 只进 DEBUG）。
     *
     * 每次 arm 一行 INFO 会在生存模式实战里刷屏（护盾一次受击 arm 32 个组），
     * 所以默认降级；需要确认「程序有没有 arm、arm 了几颗粒子」时打开。
     */
    @Volatile
    var verboseProgramLogging: Boolean = false
}
