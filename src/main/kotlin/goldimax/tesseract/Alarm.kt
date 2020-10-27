package goldimax.tesseract

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import io.ktor.util.InternalAPI
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.mamoe.mirai.event.subscribeGroupMessages
import net.mamoe.mirai.message.GroupMessageEvent
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.*
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.timerTask

fun fixRateTimer(start: Date, duration: Long, task: () -> Unit): Timer {
    var s = start.toInstant()
    while (s < Instant.now()) s += Duration.ofMillis(duration)
    val timer = Timer()
    timer.scheduleAtFixedRate(timerTask { task() }, Date.from(s), duration)
    return timer
}

@ExperimentalStdlibApi
class AlarmTransaction(val uniBot: UniBot, private val alarm: Alarm, private val group: Long): Transaction {
    var state = 0
    var time: Calendar = Calendar.getInstance()
    var duration: Duration = Duration.ofDays(1)
    @InternalAPI
    override suspend fun handle(id: UUID, message: MessageEvent) {
        when (state) {
            0 -> message.error {
                val value = message.message[PlainText]!!.content.trim()
                    .split(" ").filter { it.isNotBlank() }.map { it.trim().toInt() }
                time = Calendar.getInstance()
                time.add(Calendar.DATE, value[0])
                time[Calendar.HOUR_OF_DAY] = value[1]
                time[Calendar.MINUTE] = value[2]
                time[Calendar.SECOND] = 0
                state += 1
                val mr = message.quoteReply("Alarm at ${time.time}\n" +
                        "Reply this message to set duration,\n" +
                        "Format: hour minute")
                uniBot.actionMgr.attach(mr.source, id)
            }
            1 -> message.error {
                val value = message.message[PlainText]!!.content.trim()
                    .split(" ").filter { it.isNotBlank() }.map { it.trim().toLong() }
                check(value[0] > 0) { "no attack." }
                duration = Duration.ofHours(value[0]) + Duration.ofMinutes(value[1])
                state += 1
                val mr = message.quoteReply("Duration: $duration\n" +
                        "Reply this message to set alarm message")
                uniBot.actionMgr.attach(mr.source, id)
            }
            2 -> message.error {
                val msg = message.message.filter {
                    it is PlainText || it is Image || it is Face ||
                            it is At && it.target != uniBot.qq.id }
                val timer = fixRateTimer(time.time, duration.toMillis()) {
                    GlobalScope.launch { message.reply(msg.asMessageChain()) }
                }
                val timerID = UUID.randomUUID()
                val data = Alarm.AlarmData(timerID, time.time, duration.toMillis(),
                    group, msg.toJson(uniBot), timer)
                alarm.data.add(data)
                alarm.save()
                uniBot.actionMgr.remove(id)
                message.quoteReply("Alarm created,\n" +
                        "UUID: $timerID.")
            }
        }
    }
}

@ExperimentalStdlibApi
class Alarm(val uniBot: UniBot) {
    data class AlarmData(val id: UUID, val time: Date, val duration: Long,
                         val group: Long, val msg: JsonArray<JsonObject>, val timer: Timer)
    val data = {
        val json: JsonArray<JsonObject> =
            uniBot.getJson_("core", "key", "alarm", "json")
        json.map {
            val time = Date(it.long("time")!!)
            val duration = it.long("duration")!!
            val group = it.long("group")!!
            val msg = it.array<JsonObject>("msg")!!
            val timer = fixRateTimer(time, duration) {
                GlobalScope.launch {
                    val g = uniBot.qq.getGroup(group)
                    g.sendMessage(g.jsonMessage(uniBot, msg))
                }
            }
            AlarmData(UUID.fromString(it.string("id")!!),
                time, duration, group, msg, timer) }.toMutableList()
    }()
    fun save() {
        val json = JsonArray(data.map { JsonObject(mapOf(
            "id" to it.id.toString(), "time" to it.time.time, "duration" to it.duration,
            "group" to it.group, "msg" to it.msg)) })
        uniBot.putJson("core", "key", "alarm", "json", json)
    }
    init {
        save()
        val alarm = this
        uniBot.qq.subscribeGroupMessages {
            case("new alarm") {
                error {
                    val id = uniBot.actionMgr.insert(AlarmTransaction(uniBot, alarm, source.group.id))
                    val mr = quoteReply(
                        "Alarm creating...\n" +
                                "Reply this message to set start time,\n" +
                                "Format: offset-day hour minute"
                    )
                    uniBot.actionMgr.attach(mr.source, id)
                }
            }
            case("show all alarms") {
                error {
                    quoteReply("Alarms:\n" + data.filter { it.group == source.group.id }
                        .joinToString("\n") { it.id.toString() })
                }
            }
            startsWith("show alarm ", true) {
                error {
                    val alarmData = data.first { x -> x.id.toString() == it.trim()
                            && x.group == source.group.id }
                    quoteReply("start: ${alarmData.time}\n" +
                            "duration: ${Duration.ofMillis(alarmData.duration)}\n" +
                            source.group.jsonMessage(uniBot, alarmData.msg))
                }
            }
            startsWith("remove alarm ", true) {
                error {
                    val alarmData = data.first { x -> x.id.toString() == it.trim()
                            && x.group == source.group.id }
                    alarmData.timer.cancel()
                    alarmData.msg.forEach { if (it.string("type")!! == "image") {
                            uniBot.imageMgr.remove(UUID.fromString(it.string("value")!!))
                    } }
                    data.removeIf { it.id == alarmData.id }
                    save()
                    quoteReply("Done.")
                }
            }
        }
    }
}