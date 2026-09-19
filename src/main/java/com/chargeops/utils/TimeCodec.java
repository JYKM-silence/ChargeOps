package com.chargeops.utils;

import com.chargeops.exception.ParamErrorException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL {@code TIME} 列的编解码：{@code "HH:mm:ss"} ↔ 当日分钟数
 *
 * <h2>★ 它是为什么存在的：{@code 24:00:00} 不是时刻，是时长</h2>
 *
 * <p>项目里有四列是 MySQL 的 {@code TIME}：{@code station.open_time}、
 * {@code station.close_time}、{@code tariff_rule.start_time}、
 * {@code tariff_rule.end_time}。它们以 <b>{@code String}</b> 在实体里进出，
 * 只有真正需要做算术的地方（切分时段、比较开闭店先后）才转成分钟数 ——
 * 这里就是那唯一的转换点。
 *
 * <p><b>为什么不用 {@code java.time.LocalTime}：</b>
 * {@code LocalTime} 的上限是 23:59:59.999999999，
 * {@code 24:00:00} <b>在它的定义域之外</b>。而更本质的原因是：
 * <b>MySQL 的 {@code TIME} 根本不是「一天中的时刻」，而是有符号时长</b>
 * （范围 {@code -838:59:59} 到 {@code 838:59:59}）——
 * 这才是 {@code '24:00:00'} 合法的原因。把它映射到 {@code LocalTime}
 * 是范畴错误，只是恰好在我们常用的取值范围内能用。
 *
 * <p>已用真机验证过：{@code '24:00:00'} 写进 {@code close_time} 再读回来
 * 仍是 {@code '24:00:00'}，不会回绕成 {@code '00:00:00'}，
 * {@code TIME_TO_SEC('24:00:00')} 返回 {@code 86400}。
 *
 * <h2>为什么用 {@code String} 而不是 {@code Integer} 分钟</h2>
 *
 * <p>分钟数才是算术上「原生」的表示，但它会把这个转换推到 API、日志、
 * Swagger 示例、前端表单的每一处 —— 那些地方想看的都是 {@code "08:00:00"}。
 * 用 {@code String} 让转换<b>只在一个方向发生一次</b>，而且 MyBatis 的
 * 默认 {@code StringTypeHandler} 直接调 {@code rs.getString()}，
 * 值字节级原样进出，完全没有驱动的介入。
 *
 * <p>顺带一个好处：零填充的 {@code HH:mm:ss} 字典序和时间序一致，
 * 所以万一哪里拿它直接比较也不会错。
 */
public final class TimeCodec {

    /** 一天的分钟数。★ {@code 24:00:00} 就等于它 —— 这是半开区间收尾的表示 */
    public static final int MINUTES_PER_DAY = 1440;

    /**
     * 匹配 {@code HH:mm} 或 {@code HH:mm:ss}
     *
     * <p><b>★ 秒必须是可选的。</b>HTML 的 {@code <input type="time">}
     * 在秒为 0 时提交的是 {@code "08:00"} 而不是 {@code "08:00:00"} ——
     * 只认带秒的格式，表现是「页面上填得好好的营业时间，一保存就说格式不对」，
     * 而管理员的输入看上去完全正常。
     */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{2}):(\\d{2})(?::(\\d{2}))?$");

    private TimeCodec() {
    }

    /**
     * 把 {@code "HH:mm:ss"} / {@code "HH:mm"} 转成当日分钟数
     *
     * @return {@code 0} 到 {@code 1440}（含）。{@code "24:00:00"} 返回 {@code 1440}
     * @throws ParamErrorException 格式不对、或者超出 {@code 00:00:00 ~ 24:00:00}
     */
    public static int toMinuteOfDay(String time) {
        if (time == null || time.isBlank()) {
            throw new ParamErrorException("时间不能为空");
        }

        Matcher matcher = TIME_PATTERN.matcher(time.trim());
        if (!matcher.matches()) {
            throw new ParamErrorException("时间格式应为 HH:mm 或 HH:mm:ss，收到：" + time);
        }

        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

        // ★ 上界只允许 24:00:00 这一个值。
        //   不是「小时 <= 24」—— 那样 24:30:00 也会通过，而它在业务上无意义：
        //   我们的四列 TIME 表达的都是「当天的某个分界点」，
        //   24 点之后只能是次日 0 点。MySQL 的 TIME 能存到 838 小时是它的
        //   时长语义，不是我们这几列想要的。
        if (hour == 24 && (minute != 0 || second != 0)) {
            throw new ParamErrorException("24 点只允许写 24:00:00，收到：" + time);
        }
        if (hour > 24 || minute > 59 || second > 59) {
            throw new ParamErrorException("时间超出范围（00:00:00 ~ 24:00:00）：" + time);
        }

        return hour * 60 + minute;
    }

    /**
     * 把当日分钟数转回 {@code "HH:mm:ss"}
     *
     * @param minuteOfDay {@code 0} 到 {@code 1440}（含）
     * @throws ParamErrorException 超出范围
     */
    public static String toTimeString(int minuteOfDay) {
        if (minuteOfDay < 0 || minuteOfDay > MINUTES_PER_DAY) {
            throw new ParamErrorException("分钟数超出范围（0 ~ 1440）：" + minuteOfDay);
        }
        // 1440 单独走这条分支：1440 / 60 = 24、1440 % 60 = 0，
        // 拼出来恰好就是 "24:00:00"，不需要特判
        return String.format("%02d:%02d:00", minuteOfDay / 60, minuteOfDay % 60);
    }

    /**
     * 格式是否合法。给 DTO 的自定义校验和测试用，不会抛异常
     */
    public static boolean isValid(String time) {
        try {
            toMinuteOfDay(time);
            return true;
        } catch (ParamErrorException e) {
            return false;
        }
    }

    /**
     * 归一化成带秒的 {@code "HH:mm:ss"} 形式
     *
     * <p>入库前调用：{@code "08:00"} → {@code "08:00:00"}。
     * 不做这一步的话库里会同时存在两种写法，而 {@code "08:00" } 和
     * {@code "08:00:00"} 在 MySQL 里是同一个 TIME 值 ——
     * 也就是说不同的行看起来格式不一致，但比较起来又相等，
     * 排查数据问题时非常费解。
     */
    public static String normalize(String time) {
        return toTimeString(toMinuteOfDay(time));
    }
}
