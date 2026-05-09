
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class SineWaveMultiplierHelperV2 {

    private static final int MONEY_SCALE = 2;
    private static final int MULTIPLIER_SCALE = 4;

    /**
     * 体感模式枚举
     * GENTLE: 温和模式，周期数少，中奖波动小
     * INTENSE: 激烈模式，周期数多，中奖波动大，适合追求刺激的玩家
     */
    @Getter
    public enum VibeType {
        GENTLE(1, 0.85, 0.05),
        INTENSE(5, 0.95, 0.2);

        public final int cycles;
        public final double peakWinRate;
        public final double valleyWinRate;

        VibeType(int cycles, double peakWinRate, double valleyWinRate) {
            this.cycles = cycles;
            this.peakWinRate = peakWinRate;
            this.valleyWinRate = valleyWinRate;
        }
    }

    /**
     * 倍率计算控制器
     * 核心算法：基于正弦波的中奖控制和目标盈利管理
     */
    @Getter
    @Setter
    public static class Controller {
        private final int targetGames;
        private final BigDecimal targetSystemProfit;
        private final List<Double> sortedRates;
        private final VibeType vibeType;

        private int gamesPlayed;
        private int winCount;
        private BigDecimal totalBet;
        private BigDecimal totalPayout;
        private final Random random;

        public Controller(int targetGames, BigDecimal targetSystemProfit,
                          Map<Double, Integer> rateWeightMap, VibeType vibeType,
                          int gamesPlayed, int winCount, BigDecimal totalBet, BigDecimal totalPayout) {
            if (targetGames <= 0) {
                throw new IllegalArgumentException("targetGames must be positive");
            }
            this.targetGames = targetGames;
            // 尽量达到目标金额
            this.targetSystemProfit = targetSystemProfit.multiply(BigDecimal.valueOf(1.1));
            this.gamesPlayed = gamesPlayed;
            this.winCount = winCount;
            this.totalBet = totalBet != null ? totalBet : BigDecimal.ZERO;
            this.totalPayout = totalPayout != null ? totalPayout : BigDecimal.ZERO;
            this.random = new Random();
            this.vibeType = vibeType;

            this.sortedRates = rateWeightMap.keySet().stream().sorted().toList();

        }


        /**
         * 计算本轮倍率
         * 核心算法入口，根据当前状态决定是否中奖及倍率
         *
         * @param playerBet 玩家投注金额
         * @return 倍率值，0表示不中奖
         */
        public Double calculateMultiplier(BigDecimal playerBet) {
            if (gamesPlayed >= targetGames) {
                log.warn("[SineWave] 已达目标局数 {}", targetGames);
                return 0.0;
            }

            gamesPlayed++;
            totalBet = totalBet.add(playerBet);

            log.info("[SineWave] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("[SineWave] 第{}/{}局 | 本局投注:{}", gamesPlayed, targetGames, playerBet);


            // 当前系统输赢金额: 总投注金额 - 总派奖金额, 如果为正数则代表系统赢, 如果为负数则代表玩家赢
            BigDecimal currentProfit = totalBet.subtract(totalPayout);
            int remaining = targetGames - gamesPlayed + 1;
            BigDecimal gap = targetSystemProfit.subtract(currentProfit);


            if (gap.compareTo(BigDecimal.ZERO) >= 0 || remaining <= 0) {
                log.info("[SineWave] ✅ 已达标停止派奖 | 当前盈利:{} | 目标:{}",
                        currentProfit, targetSystemProfit);
                return 0.0;
            }

            // 1.0. 计算应当派奖金额 = 目标系统金额 / 剩余局数
            BigDecimal payoutNeeded = gap
                    .abs()
                    .divide(
                            BigDecimal.valueOf(remaining)
                            , MONEY_SCALE
                            , RoundingMode.HALF_UP
                    );

            log.info("[SineWave] 📊 距目标差:{} | 剩余局数:{} | 基础派奖:{}", gap, remaining, payoutNeeded);

            // 0. 总中奖率45%
            var canWinRandom = random.nextDouble();
            boolean needWin = payoutNeeded.compareTo(gap
                    .abs().multiply(BigDecimal.valueOf(0.3))) > 0;
            if (needWin) {
                canWinRandom = 0.4;
                log.info("[SineWave] 🎉 需要触发中奖 | 已经相差过多:{}, {}", payoutNeeded, gap
                        .abs().multiply(BigDecimal.valueOf(0.3)));
            }
            if (canWinRandom < 0.45) {
                val selectExactMultiplierRes = this.selectExactMultiplier(
                        playerBet,
                        payoutNeeded.multiply(
                                BigDecimal.ONE
//                                BigDecimal.valueOf(1 + this.calculateDynamicWinRate(gamesPlayed))
                                // 局数越多，后期可能翻倍越高
//                                BigDecimal.valueOf(gamesPlayed * this.vibeType.valleyWinRate)

                        )
                );
                this.setWinCount(winCount + 1);
                this.setTotalPayout(
                        this.totalPayout.add(
                                BigDecimal.valueOf(selectExactMultiplierRes)
                                        .multiply(playerBet)
                                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        )
                );
                log.info("[SineWave] ✨ 普奖 | 选中倍率:{}", selectExactMultiplierRes);
                log.info("[SineWave]    累计投注:{} | 累计派奖:{} | 系统盈利:{}", totalBet, totalPayout, totalBet.subtract(totalPayout));
                return selectExactMultiplierRes;
            }


            return 0.0;
        }

        /**
         * 精确选择倍率（用于精确纠偏）
         * 修复: 添加随机性，从不超过目标的候选倍率中随机选择一个
         */
        private Double selectExactMultiplier(BigDecimal playerBet, BigDecimal payoutNeeded) {
            if (sortedRates.isEmpty()) {
                return 0.0;
            }

            BigDecimal targetMult = payoutNeeded.divide(playerBet, MULTIPLIER_SCALE, RoundingMode.HALF_UP);
            double target = targetMult.doubleValue();

            log.info("[SineWave] 🔍 精确纠偏 | 需派奖:{} | 目标倍率:{}", payoutNeeded, String.format("%.2f", target));

            if (target <= 0) {
                return sortedRates.getFirst();
            }

            // 收集所有不超过目标的候选倍率
            List<Double> candidates = new ArrayList<>();
            for (Double rate : sortedRates) {
                if (rate <= target && rate >= target * 0.6) {
                    candidates.add(rate);
                }
            }

            if (!candidates.isEmpty()) {
                // 从候选倍率中随机选择一个，增加随机性
                if (random.nextDouble() < 0.2) {
                    return candidates.get(random.nextInt(candidates.size()));
                }
                return this.selectTopBigRate(candidates);
            }

            // 如果所有倍率都超过目标，选择最小的那个
            return sortedRates.getFirst();
        }


        private Double selectTopBigRate(List<Double> bigRates) {
            if (bigRates.isEmpty()) {
                return sortedRates.getLast();
            }
            List<Double> sorted = bigRates.stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(5)
                    .toList();
            double roll = random.nextDouble();
            if (roll < 0.25 || sorted.size() == 1) {
                return sorted.getFirst();
            } else if (roll < 0.35 || sorted.size() == 2) {
                return sorted.get(1);
            } else if (roll < 0.40 || sorted.size() == 3) {
                return sorted.get(Math.min(2, sorted.size() - 1));
            } else if (roll < 0.55 || sorted.size() == 4) {
                return sorted.get(Math.min(3, sorted.size() - 1));
            } else {
                return sorted.getLast();
            }
        }

        private double calculateDynamicWinRate(int currentGame) {

            double phase = currentGame * vibeType.cycles * 2 * Math.PI;

            double sinValue = Math.sin(phase);

            double normalizedSin = (sinValue + 1) / 2;

            double winRate = vibeType.valleyWinRate +
                    normalizedSin * (vibeType.peakWinRate - vibeType.valleyWinRate);

            return Math.max(0.1, Math.min(0.95, winRate));
        }

        /**
         * 创建状态快照，用于持久化和恢复
         */
        public ControllerState snapshot() {
            return new ControllerState(gamesPlayed, winCount, totalBet, totalPayout);
        }

        /**
         * 从快照恢复控制器状态
         */
        public static Controller fromSnapshot(ControllerState state,
                                              int targetGames,
                                              BigDecimal targetSystemProfit,
                                              Map<Double, Integer> rateWeightMap,
                                              VibeType vibeType) {
            return new Controller(targetGames, targetSystemProfit, rateWeightMap, vibeType,
                    state.gamesPlayed, state.winCount, state.totalBet, state.totalPayout);
        }

        public BigDecimal getSystemProfit() {
            return totalBet.subtract(totalPayout);
        }
    }

    /**
     * 控制器状态，用于持久化
     */
    /**
     * 控制器状态，用于持久化
     */
    @Data
    public static class ControllerState {
        private final int gamesPlayed;
        private final int winCount;
        private final BigDecimal totalBet;
        private final BigDecimal totalPayout;

        public ControllerState(int gamesPlayed, int winCount, BigDecimal totalBet, BigDecimal totalPayout) {
            this.gamesPlayed = gamesPlayed;
            this.winCount = winCount;
            this.totalBet = totalBet;
            this.totalPayout = totalPayout;
        }

        @Override
        public String toString() {
            return String.format("State{games=%d, wins=%d, bet=%s, payout=%s}",
                    gamesPlayed, winCount, totalBet, totalPayout);
        }
    }







    // --------------------------------------------------------------------------------------------------------




    /**
     * 测试入口
     */
    public static void main(String[] args) throws JsonProcessingException, UnsupportedEncodingException {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        String str = """
                {"0.0":1,"0.3":3964,"0.5":3070,"0.6":1267,"0.8":167,"0.9":479,"1.0":3043,"1.1":83,"1.2":148,"1.3":175,"1.4":40,"1.5":856,"1.6":61,"1.7":17,"1.8":76,"1.9":32,"2.0":1907,"2.1":40,"2.2":6,"2.3":114,"2.4":13,"2.5":357,"2.6":30,"2.7":35,"2.8":25,"2.9":9,"3.0":581,"3.1":19,"3.2":11,"3.3":28,"3.5":108,"3.6":12,"3.8":8,"3.9":4,"4.0":467,"4.3":17,"4.4":1,"4.5":38,"4.6":6,"4.8":6,"4.9":8,"5.0":207,"5.3":8,"5.5":1,"5.6":1,"5.7":3,"5.8":1,"6.0":40,"6.1":3,"6.2":4,"6.3":1,"6.5":1,"6.6":2,"6.9":1,"7.0":4,"7.3":2,"7.5":1,"7.6":1,"7.9":1,"8.0":21,"8.3":1,"8.9":1,"9.0":2,"10.0":552,"10.3":16,"10.5":17,"10.6":1,"10.9":2,"11.0":16,"11.2":1,"11.4":1,"11.5":1,"11.7":1,"11.9":2,"12.0":9,"12.1":1,"12.5":1,"12.6":1,"13.0":1,"14.0":2,"14.6":1,"14.9":1,"15.0":25,"15.6":1,"16.0":2,"17.0":1,"20.0":199,"20.3":1,"20.5":1,"20.6":1,"21.0":1,"21.2":1,"21.5":1,"22.0":1,"22.5":1,"23.3":1,"24.0":1,"24.3":1,"24.5":1,"25.0":1,"25.3":1,"25.5":1,"26.0":1,"26.5":1,"27.0":1,"30.0":99,"30.3":1,"30.5":1,"30.6":1,"31.0":1,"31.5":1,"32.0":1,"35.0":1,"35.3":1,"40.0":6,"40.3":1,"40.5":1,"41.0":1,"45.0":1,"50.0":94,"50.3":1,"50.6":1,"50.9":1,"51.0":1,"55.0":1,"60.0":14,"60.5":1,"70.0":1,"70.9":1,"81.3":1,"110.0":1}
                """;

        ObjectMapper mapper = new ObjectMapper();
        Map<Double, Integer> rates = mapper.convertValue(mapper.readTree(str), new TypeReference<Map<Double, Integer>>() {
                })
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        BigDecimal target = new BigDecimal("-5000");
        int targetSpinNum = 100;

        runTestIntense(targetSpinNum, target, rates, VibeType.INTENSE);


    }

    @SneakyThrows
    private static void runTestIntense(int targetSpinNum, BigDecimal target, Map<Double, Integer> rates, VibeType vibeType) {
        Controller ctrl = new Controller(targetSpinNum, target, rates, vibeType,
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO);

        int winCount = 0;
        int loseCount = 0;
        int highWinCount = 0;
        int smallWinCount = 0;
        val rateWinMap = new HashMap<>();

        System.out.println(ctrl.calculateDynamicWinRate(1));

        for (int i = 1; i <= targetSpinNum; i++) {
            BigDecimal bet = new BigDecimal(4 + Math.random() * 6).setScale(2, RoundingMode.HALF_UP);

            bet = new BigDecimal("4");

            if (i > 1) {
                ControllerState savedState = ctrl.snapshot();
                ctrl = Controller.fromSnapshot(savedState, targetSpinNum, target, rates, vibeType);
            }

            Double mul = ctrl.calculateMultiplier(bet);
            rateWinMap.put(i, mul);
            if (mul > 0) {
                winCount++;
                if (mul >= 20) highWinCount++;
                else smallWinCount++;
                System.out.printf("第%d局 中奖! 倍率:%.2f 投注:%.2f 派奖:%.2f 系统盈利:%.2f\n",
                        i, mul, bet, bet.multiply(new BigDecimal(mul)), ctrl.getSystemProfit());
            } else {
                loseCount++;
            }


            // 当前系统输赢金额: 总投注金额 - 总派奖金额, 如果为正数则代表系统赢, 如果为负数则代表玩家赢
            BigDecimal currentProfit = ctrl.getTotalBet().subtract(ctrl.getTotalPayout());
            int remaining = ctrl.getTargetGames() - ctrl.getGamesPlayed() + 1;
            BigDecimal gap = ctrl.getTargetSystemProfit().subtract(currentProfit);


            if (gap.compareTo(BigDecimal.ZERO) >= 0 || remaining <= 0) {
                log.info("[SineWave] ✅ 已达标停止派奖 | 当前盈利:{} | 目标:{}",
                        currentProfit, ctrl.getTargetSystemProfit());
                break;
            }
        }

        System.out.printf("\n========== 最终结果 ==========\n");
        System.out.printf("体感模式: %s\n", vibeType);
        System.out.printf("中奖局数: %d (小奖<20: %d, 大奖>=20: %d), 未中奖: %d, 中奖率: %.1f%%\n",
                winCount, smallWinCount, highWinCount, loseCount,
                winCount * 100.0 / targetSpinNum);
        System.out.printf("总投注: %.2f\n", ctrl.getTotalBet());
        System.out.printf("总派奖: %.2f\n", ctrl.getTotalPayout());
        System.out.printf("系统盈利: %.2f (目标: %.2f)\n", ctrl.getSystemProfit(), target);
        System.out.printf("偏差: %.2f%%\n", ctrl.getSystemProfit().subtract(target).abs()
                .divide(target.abs(), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
        System.out.printf("中奖分布: %s\n", new ObjectMapper().writeValueAsString(rateWinMap));
    }

}
