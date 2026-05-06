import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.Getter;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 倍率计算控制器 V5 - 分段返奖 + 段尾追赶机制
 *
 * 核心改进 (基于V4):
 * 1. 分段中奖分布: 按百分比分段，每段独立中奖规划
 * 2. 段尾爆大奖: 段的后20%位置触发追赶，计算 gap/bet+1
 * 3. 目标必成: 尽量在80%%前完成目标，完成后提前终止投注
 * 4. 结果导向: 完成目标即停止，不强制消耗完所有局数
 * 5. 均匀分布: 每段结构相同，大奖分散在各段尾
 *
 * 分段策略:
 * - 分段大小: 10% (可配置)
 * - 段内结构: 前80%%小奖/不中，后20%段尾大奖追赶
 * - 追赶公式: neededMultiplier = gap/bet + 1
 */
public class SineWaveMultiplierHelperV2 {

    private static final boolean DEBUG = true;
    private static void log(String msg, Object... args) {
        if (DEBUG) {
            // 将 {} 替换为 %s 以兼容 String.format
            String format = msg.replace("{}", "%s");
            System.out.printf(format + "%n", args);
        }
    }

    @Getter
    public enum VibeType {
        GENTLE(1, 0.85, 0.85),
        INTENSE(2, 0.95, 0.95);
        public final int cycles;
        public final double peakWinRate;
        public final double valleyWinRate;
        VibeType(int cycles, double peakWinRate, double valleyWinRate) {
            this.cycles = cycles;
            this.peakWinRate = peakWinRate;
            this.valleyWinRate = valleyWinRate;
        }
    }

    private static final int MONEY_SCALE = 2;
    private static final int MULTIPLIER_SCALE = 4;

    // ========== 核心常量 ==========
    private static final double BASE_WIN_RATE = 0.40;       // 基础中奖率 40%
    private static final double MAX_WIN_RATE = 0.60;        // 最大中奖率 60%
    private static final double BIG_WIN_RATIO = 0.50;       // 大奖占中奖 50%
    private static final double TOLERANCE = 0.10;           // 偏差容忍 10%
    private static final double BIG_WIN_THRESHOLD = 20.0;   // 大奖倍率分界线 (>20)

    @Getter
    public static class Controller {
        private final int targetGames;
        private final BigDecimal targetSystemProfit;
        private final List<Double> sortedRates;
        private final VibeType vibeType;

        private int gamesPlayed;
        private int winCount;
        private int bigWinCount;
        private BigDecimal totalBet;
        private BigDecimal totalPayout;
        private final Random random;

        // 动态中奖配额
        private int totalWinsPlanned;
        private int bigWinsPlanned;
        private int smallWinsPlanned;

        // 预规划中奖方案
        private final int[] winPlan;           // -1=不规划, 0=小奖, 1=大奖
        private final boolean planInitialized;
        private final List<BigDecimal> betList;

        // ========== V5 分段返奖新增字段 ==========
        // 分段配置
        private final double segmentSizePercent;  // 分段大小百分比 (默认10%)
        private final boolean segmentedMode;      // 是否启用分段模式
        private final int segmentSize;            // 分段局数
        private final int totalSegments;         // 总段数

        // 分段状态追踪
        private int currentSegment;               // 当前段编号 (0-based)
        private int segmentGamesPlayed;          // 当前段内已玩局数
        private BigDecimal segmentStartProfit;    // 段开始时的累计盈利
        private boolean targetAchievedEarly;      // 是否已提前达成目标

        // 段尾追赶配置
        private final double segmentTailPercent;  // 段尾百分比 (默认20%)
        private final double chaseMultiplier;    // 追赶系数 (默认1.0)

        // 分散性追踪
        private int consecutiveWins = 0;
        private int consecutiveBigWins = 0;
        private int lastWinGame = -1;
        private int lastBigWinGame = -1;
        private String lastWinType = null;

        // 波动性追踪
        private final List<Double> bigWinMultipliers = new ArrayList<>();
        private final List<Double> smallWinMultipliers = new ArrayList<>();

        public Controller(int targetGames, BigDecimal targetSystemProfit,
                          Map<Double, Integer> rateWeightMap, VibeType vibeType,
                          List<BigDecimal> betList,
                          int gamesPlayed, int winCount, BigDecimal totalBet, BigDecimal totalPayout) {
            this(targetGames, targetSystemProfit, rateWeightMap, vibeType, betList,
                 gamesPlayed, winCount, totalBet, totalPayout,
                 0.10, true, 0.20, 1.0);  // 默认分段模式
        }

        public Controller(int targetGames, BigDecimal targetSystemProfit,
                          Map<Double, Integer> rateWeightMap, VibeType vibeType,
                          List<BigDecimal> betList,
                          int gamesPlayed, int winCount, BigDecimal totalBet, BigDecimal totalPayout,
                          double segmentSizePercent, boolean segmentedMode,
                          double segmentTailPercent, double chaseMultiplier) {
            if (targetGames <= 0) throw new IllegalArgumentException("targetGames must be positive");
            this.targetGames = targetGames;
            this.targetSystemProfit = targetSystemProfit;
            this.gamesPlayed = gamesPlayed;
            this.winCount = winCount;
            this.bigWinCount = 0;
            this.totalBet = totalBet != null ? totalBet : BigDecimal.ZERO;
            this.totalPayout = totalPayout != null ? totalPayout : BigDecimal.ZERO;
            this.random = new Random();
            this.vibeType = vibeType;
            this.sortedRates = Collections.unmodifiableList(new ArrayList<>(rateWeightMap.keySet()));
            this.betList = betList != null ? new ArrayList<>(betList) : new ArrayList<>();

            // V5 分段模式配置
            this.segmentSizePercent = segmentSizePercent;
            this.segmentedMode = segmentedMode;
            this.segmentTailPercent = segmentTailPercent;
            this.chaseMultiplier = chaseMultiplier;
            this.segmentSize = Math.max(1, (int) Math.ceil(targetGames * segmentSizePercent));
            this.totalSegments = (int) Math.ceil((double) targetGames / segmentSize);
            this.currentSegment = 0;
            this.segmentGamesPlayed = 0;
            this.segmentStartProfit = BigDecimal.ZERO;
            this.targetAchievedEarly = false;

            // 初始化中奖规划
            this.winPlan = new int[targetGames];
            Arrays.fill(this.winPlan, -1);
            this.planInitialized = betList != null && !betList.isEmpty();

            // 如果有投注列表，预规划中奖方案
            if (planInitialized) {
                if (segmentedMode) {
                    planSegmentedWinDistribution();
                } else {
                    planWinDistribution();
                }
            }

            log("[V5] 初始化 | 目标局:{} | 目标盈利:{} | 体感:{} | 分段模式:{} | 段大小:{} | 计划中奖:{}(大奖{} 小奖{}) | 倍率数:{}",
                    targetGames, targetSystemProfit, vibeType, segmentedMode ? "开" : "关",
                    segmentSize, totalWinsPlanned, bigWinsPlanned, smallWinsPlanned, sortedRates.size());
        }

        /**
         * 预规划中奖分配方案
         *
         * 核心思路:
         * 1. 计算需要派出的总金额
         * 2. 根据目标金额决定中奖率(40%-60%)
         * 3. 在大投注局优先分配大奖以提高派奖效率
         * 4. 调整中奖位置确保分散性
         */
        private void planWinDistribution() {
            int n = Math.min(betList.size(), targetGames);

            // 计算预估总投注
            BigDecimal estimatedTotalBet = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                estimatedTotalBet = estimatedTotalBet.add(betList.get(i));
            }

            // 需要派奖总金额 = 总投注 - 目标盈利
            // 例如：目标-5000，总投注120，派奖需要 = 120 - (-5000) = 5120
            BigDecimal requiredPayout = estimatedTotalBet.subtract(targetSystemProfit);

            log("[V4] 预规划 | 总投注:{} | 需要派奖:{} | 目标:{}",
                    estimatedTotalBet, requiredPayout, targetSystemProfit);

            // 分析大奖池和小奖池
            List<Double> bigRates = sortedRates.stream().filter(r -> r > BIG_WIN_THRESHOLD).sorted().toList();
            List<Double> smallRates = sortedRates.stream().filter(r -> r > 0 && r <= BIG_WIN_THRESHOLD).sorted().toList();

            double avgBigRate = bigRates.isEmpty() ? 50.0 : bigRates.stream().mapToDouble(Double::doubleValue).average().orElse(50.0);
            double avgSmallRate = smallRates.isEmpty() ? 10.0 : smallRates.stream().mapToDouble(Double::doubleValue).average().orElse(10.0);

            // 计算最小可能派奖和最大可能派奖
            double minPayoutPerWin = avgSmallRate;  // 小奖平均倍率
            double maxPayoutPerWin = avgBigRate;    // 大奖平均倍率

            // 迭代调整中奖率找到最优方案
            double bestWinRate = BASE_WIN_RATE;
            BigDecimal bestPayoutDiff = null;

            for (double winRate = BASE_WIN_RATE; winRate <= MAX_WIN_RATE; winRate += 0.02) {
                int winsCount = (int) Math.floor(n * winRate);
                int bigWins = winsCount / 2;  // 大奖小奖各半
                int smallWins = winsCount - bigWins;

                // 计算理论派奖（需要考虑投注分布）
                BigDecimal theoreticalPayout = calculateTheoreticalPayout(n, winsCount, bigWins, avgBigRate, avgSmallRate);

                BigDecimal diff = theoreticalPayout.subtract(requiredPayout).abs();

                if (bestPayoutDiff == null || diff.compareTo(bestPayoutDiff) < 0) {
                    bestPayoutDiff = diff;
                    bestWinRate = winRate;
                }
            }

            // 设置最终中奖配额
            this.totalWinsPlanned = (int) Math.floor(n * bestWinRate);
            this.bigWinsPlanned = this.totalWinsPlanned / 2;
            this.smallWinsPlanned = this.totalWinsPlanned - this.bigWinsPlanned;

            log("[V4] 调整后 | 中奖率:{}%% | 大奖:{} | 小奖:{}",
                    String.format("%.0f", bestWinRate * 100), bigWinsPlanned, smallWinsPlanned);

            // 执行中奖分配
            allocateWins(n, requiredPayout, bigRates, smallRates, avgBigRate, avgSmallRate);
        }

    /**
     * V5 分段预规划中奖分配方案
     *
     * 分段策略:
     * - 每段长度: segmentSize 局
     * - 段内结构: 前80%%小奖/不中，后20%段尾大奖
     * - 段尾大奖用于追赶阶段目标
     * - 尽量在80%%局数前完成整体目标
     *
     * 中奖率控制: 总中奖率不超过60%
     */
    private void planSegmentedWinDistribution() {
        int n = Math.min(betList.size(), targetGames);

        // 计算预估总投注
        BigDecimal estimatedTotalBet = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            estimatedTotalBet = estimatedTotalBet.add(betList.get(i));
        }

        log("[V5-分段] 总投注:%s | 总段数:%d | 每段局数:%d", estimatedTotalBet, totalSegments, segmentSize);

        // 中奖率控制: 严格限制在60%以内
        // 计算可达的最大中奖次数 (60% of n)
        int maxWins = (int) Math.floor(n * 0.60);

        // 估算需要的中奖次数来达成目标
        BigDecimal totalRequiredPayout = estimatedTotalBet.subtract(targetSystemProfit);
        int estimatedNeededWins = calculateNeededWins(n, totalRequiredPayout);

        // 使用较少的中奖次数（确保不超过60%，且足够达成目标）
        int plannedTotalWins = Math.min(maxWins, Math.max(estimatedNeededWins, 2));

        // 确保大奖占比在40-60%之间
        int plannedBigWins = (int) Math.round(plannedTotalWins * 0.50); // 大约占50%
        int plannedSmallWins = plannedTotalWins - plannedBigWins;

        log("[V5-分段] 规划中奖: 总%d(大奖%d 小奖%d) | 估算需要:%d | 最大允许:%d",
                plannedTotalWins, plannedBigWins, plannedSmallWins, estimatedNeededWins, maxWins);

        // 为每段规划中奖 - 简化版：均匀分配
        int winsPerSegment = (int) Math.ceil((double) plannedTotalWins / totalSegments);
        int bigWinsPerSegment = (int) Math.ceil((double) plannedBigWins / totalSegments);

        int bigWinsAllocated = 0;
        int smallWinsAllocated = 0;

        // 第一步：分配大奖到各段的段尾位置
        for (int seg = 0; seg < totalSegments && bigWinsAllocated < plannedBigWins; seg++) {
            int segStart = seg * segmentSize;
            int segEnd = Math.min(segStart + segmentSize, n);
            int segLength = segEnd - segStart;

            if (segLength <= 0) break;

            // 每段段尾分配1个大奖
            int allocIdx = segEnd - 1;  // 段尾最后一个位置
            if (winPlan[allocIdx] == -1) {
                winPlan[allocIdx] = 1;  // 大奖
                bigWinsAllocated++;
            }
        }

        // 第二步：分配小奖到剩余位置
        for (int i = 0; i < n && smallWinsAllocated < plannedSmallWins; i++) {
            if (winPlan[i] == -1) {
                winPlan[i] = 0;  // 小奖
                smallWinsAllocated++;
            }
        }

        this.totalWinsPlanned = bigWinsAllocated + smallWinsAllocated;
        this.bigWinsPlanned = bigWinsAllocated;
        this.smallWinsPlanned = smallWinsAllocated;

        log("[V5-分段] 规划完成 | 总中奖:%d | 大奖:%d | 小奖:%d",
                totalWinsPlanned, bigWinsPlanned, smallWinsPlanned);
    }

    /**
     * 计算需要的中奖次数
     */
    private int calculateNeededWins(int n, BigDecimal requiredPayout) {
        if (requiredPayout.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        // 计算平均投注
        BigDecimal avgBet = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            avgBet = avgBet.add(betList.get(i));
        }
        avgBet = avgBet.divide(new BigDecimal(n), 2, RoundingMode.HALF_UP);

        // 分析大奖池和小奖池
        List<Double> bigRates = sortedRates.stream().filter(r -> r > BIG_WIN_THRESHOLD).sorted().toList();
        List<Double> smallRates = sortedRates.stream().filter(r -> r > 0 && r <= BIG_WIN_THRESHOLD).sorted().toList();

        // 计算平均中奖倍率（大奖50%，小奖50%）
        double avgBigRate = bigRates.isEmpty() ? 50.0 : bigRates.stream().mapToDouble(Double::doubleValue).average().orElse(50.0);
        double avgSmallRate = smallRates.isEmpty() ? 5.0 : smallRates.stream().mapToDouble(Double::doubleValue).average().orElse(5.0);
        double weightedAvgRate = avgBigRate * 0.50 + avgSmallRate * 0.50;

        // 估算需要的中奖次数
        double avgBetD = avgBet.doubleValue();
        int neededWins = (int) Math.ceil(requiredPayout.doubleValue() / (avgBetD * weightedAvgRate));

        log("[V5-分段] 估算需要中奖:%d次 (派奖:%s, avgBet:%.1f, weightedAvg:%.1f)",
                neededWins, requiredPayout, avgBetD, weightedAvgRate);

        return neededWins;
    }

        /**
         * 计算理论派奖金额
         */
        private BigDecimal calculateTheoreticalPayout(int n, int winsCount, int bigWins, double avgBigRate, double avgSmallRate) {
            if (winsCount == 0) return BigDecimal.ZERO;

            // 按投注金额排序，大投注优先
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < n; i++) indices.add(i);
            indices.sort((a, b) -> betList.get(b).compareTo(betList.get(a)));

            BigDecimal payout = BigDecimal.ZERO;
            int bigAllocated = 0, smallAllocated = 0;

            for (int i = 0; i < winsCount && i < indices.size(); i++) {
                int idx = indices.get(i);
                BigDecimal bet = betList.get(idx);

                if (bigAllocated < bigWins) {
                    payout = payout.add(bet.multiply(new BigDecimal(avgBigRate)));
                    bigAllocated++;
                } else if (smallAllocated < winsCount - bigWins) {
                    payout = payout.add(bet.multiply(new BigDecimal(avgSmallRate)));
                    smallAllocated++;
                }
            }

            return payout;
        }

        /**
         * 分配中奖
         */
        private void allocateWins(int n, BigDecimal requiredPayout, List<Double> bigRates, List<Double> smallRates,
                                double avgBigRate, double avgSmallRate) {
            // 按投注金额降序排列索引
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < n; i++) indices.add(i);
            indices.sort((a, b) -> betList.get(b).compareTo(betList.get(a)));

            // 分配大奖（优先大投注局）
            int bigAllocated = 0;
            for (int idx : indices) {
                if (bigAllocated >= bigWinsPlanned) break;
                if (consecutiveBigWins >= 1) continue;  // 避免连续大奖

                winPlan[idx] = 1;  // 大奖
                bigAllocated++;
            }

            // 分配小奖（填充剩余位置）
            int smallAllocated = 0;
            for (int idx : indices) {
                if (winPlan[idx] != -1) continue;  // 跳过已分配大奖的
                if (smallAllocated >= smallWinsPlanned) break;

                // 避免连续中奖
                if (consecutiveWins >= 2) continue;

                winPlan[idx] = 0;  // 小奖
                smallAllocated++;
            }

            // 打散分配结果
            disperseWins(n);
        }

        /**
         * 打散中奖分配
         */
        private void disperseWins(int n) {
            List<Integer> winIndices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (winPlan[i] != -1) winIndices.add(i);
            }

            // 避免连续中奖
            for (int i = 1; i < winIndices.size(); i++) {
                if (winIndices.get(i) - winIndices.get(i - 1) == 1) {
                    // 连续中奖，找一个远处的空位交换
                    for (int j = n - 1; j > i; j--) {
                        if (winPlan[j] == -1 && Math.abs(j - winIndices.get(i)) > 2) {
                            winPlan[winIndices.get(i)] = -1;
                            winPlan[j] = winPlan[winIndices.get(i - 1)] == 1 ? 1 : 0;
                            winIndices.set(i, j);
                            break;
                        }
                    }
                }
            }

            // 避免连续大奖
            List<Integer> bigWinIndices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (winPlan[i] == 1) bigWinIndices.add(i);
            }

            for (int i = 1; i < bigWinIndices.size(); i++) {
                if (bigWinIndices.get(i) - bigWinIndices.get(i - 1) == 1) {
                    // 连续大奖，找一个小奖位交换
                    for (int j = 0; j < n; j++) {
                        if (winPlan[j] == 0 && Math.abs(j - bigWinIndices.get(i)) > 2) {
                            winPlan[bigWinIndices.get(i)] = 0;
                            winPlan[j] = 1;
                            bigWinIndices.set(i, j);
                            break;
                        }
                    }
                }
            }
        }

        /**
         * 计算目标盈利所需的中奖金额
         * 系统输赢金额 = 总投注 - 总派奖 = 目标盈利
         * => 总派奖 = 总投注 - 目标盈利
         */
        private BigDecimal calculateRequiredPayout(BigDecimal totalBets) {
            return totalBets.subtract(targetSystemProfit);
        }

        /**
         * 动态决定是否中奖 - 核心算法 (V5)
         */
        public Double calculateMultiplier(BigDecimal playerBet) {
            if (gamesPlayed >= targetGames) {
                System.err.printf("[V5] 已达目标局数 %d%n", targetGames);
                return 0.0;
            }

            int currentGameIndex = gamesPlayed;
            gamesPlayed++;
            totalBet = totalBet.add(playerBet);

            // V5: 提前终止检查 - 必须达到80%%局数且目标已达成，才能停止
            // 80%%阈值：确保至少玩到80%%的局数才允许完成目标
            double earlyStopThreshold = 0.80;
            boolean hasPlayedEnough = gamesPlayed >= targetGames * earlyStopThreshold;
            if (hasPlayedEnough && isTargetAchieved()) {
                targetAchievedEarly = true;
                log("[V5] 目标已达成({}) | 已玩{}局/{}局({}) | 停止投注 | 最终盈利:{}",
                        getSystemProfit().subtract(targetSystemProfit).abs().compareTo(new BigDecimal("1")) <= 0 ? "精确" : "接近",
                        gamesPlayed, targetGames, String.format("%.0f%%", (double)gamesPlayed / targetGames * 100),
                        getSystemProfit());
                return 0.0;
            }

            Double multiplier = 0.0;
            String reason = "不中";

            // 检查预规划
            if (planInitialized && currentGameIndex < winPlan.length) {
                int planned = winPlan[currentGameIndex];
                if (planned == 1) {
                    // 预规划大奖
                    if (segmentedMode) {
                        if (isPositiveTarget()) {
                            // 正目标：系统要赢，不应该派大奖，给小奖或不中
                            multiplier = 0.0;
                            reason = "正目标不派大奖";
                        } else {
                            // 负目标：V5 分段模式段尾大奖用追赶公式
                            multiplier = selectSegmentTailChaseMultiplier(playerBet);
                            reason = "段尾追赶大奖";
                        }
                    } else {
                        multiplier = selectMultiplierForBet(true, playerBet);
                        reason = "预规划大奖";
                    }

                    if (multiplier > 0) {
                        bigWinCount++;
                        bigWinMultipliers.add(multiplier);
                        winCount++;
                        totalPayout = totalPayout.add(
                                playerBet.multiply(new BigDecimal(multiplier)).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                        checkEarlyCompletion();
                    }
                } else if (planned == 0) {
                    // 预规划小奖 - 正目标给更多小奖，负目标给极少小奖
                    if (isPositiveTarget()) {
                        // 正目标：可以给稍大的小奖，让玩家开心但不亏本
                        multiplier = selectPositiveTargetSmallWin(playerBet);
                        reason = "正目标小奖";
                    } else {
                        // 负目标：小奖尽量小
                        multiplier = selectSmallWinMultiplier(playerBet);
                        reason = "小奖";
                    }
                    winCount++;
                    smallWinMultipliers.add(multiplier);
                    totalPayout = totalPayout.add(
                            playerBet.multiply(new BigDecimal(multiplier)).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                }
            } else {
                // 无预规划或无中奖计划 - 不中奖
                multiplier = 0.0;
                reason = "无计划不中";
            }

            updateDispersionTracking(multiplier != null ? multiplier : 0.0, currentGameIndex);

            log("[V5] {}/{} | 投注:{} | 倍率:{} | gap:{} | 盈利:{} | {}",
                    gamesPlayed, targetGames, playerBet,
                    String.format("%.1f", multiplier != null ? multiplier : 0.0),
                    calculateProfitGap(), getSystemProfit(), reason);

            return multiplier != null ? multiplier : 0.0;
        }

        /**
         * V5: 检查目标是否已达成
         *
         * 目标判断逻辑：
         * - 负目标（如-5000）：|盈利| >= |目标| 表示达标（即盈利 <= 目标，因为都是负数）
         * - 正目标（如+1200）：盈利 >= 目标 表示达标
         */
        private boolean isTargetAchieved() {
            BigDecimal profit = getSystemProfit();

            if (targetSystemProfit.compareTo(BigDecimal.ZERO) < 0) {
                // 负目标：盈利 <= 目标（如-5500 <= -5000，因为-5500更负）
                // 但要注意：-1100 > -1200，所以-1100没达标
                // 达标意味着盈利的绝对值 >= 目标绝对值
                return profit.abs().compareTo(targetSystemProfit.abs()) >= 0;
            } else {
                // 正目标：盈利 >= 目标（如+1300 >= +1200）
                return profit.compareTo(targetSystemProfit) >= 0;
            }
        }

        /**
         * V5: 判断当前是否为正目标模式（系统要赚钱）
         */
        private boolean isPositiveTarget() {
            return targetSystemProfit.compareTo(BigDecimal.ZERO) > 0;
        }

        /**
         * V5: 检查是否提前完成目标
         */
        private void checkEarlyCompletion() {
            if (!targetAchievedEarly && isTargetAchieved()) {
                log("[V5] === 提前达成目标！当前局数:{}/{} | 盈利:{} | 目标:{} ===",
                        gamesPlayed, targetGames, getSystemProfit(), targetSystemProfit);
            }
        }

    /**
     * V5:段尾追赶大奖 - 随机选择，但限制覆盖缺口比例
     *
     * 新逻辑:
     * - 缺口小（gapRatio <= 20）：从 3x~20x 之间随机选
     * - 缺口大（gapRatio > 20）：从 20x~gapRatio×0.8 之间随机选
     * - 每次最多覆盖缺口的 80%，避免一次性完成目标
     */
    private Double selectSegmentTailChaseMultiplier(BigDecimal playerBet) {
        // 计算gap = 总投注 - 总派奖 - 目标盈利
        BigDecimal gap = calculateProfitGap().abs();

        // gapRatio = 缺口需要的倍率（如果全用单次大奖覆盖需要多少倍）
        double gapRatio = gap.divide(playerBet, 4, RoundingMode.HALF_UP).doubleValue();

        // 每次最多覆盖缺口的 80%
        double maxCoverRatio = 0.8;
        double maxAllowedMultiplier = gapRatio * maxCoverRatio;

        log("[V5-追赶] gap={} 投注={} gapRatio={:.2f} 最多覆盖80%={:.2f}",
                gap, playerBet, gapRatio, maxAllowedMultiplier);

        // 候选倍率列表
        List<Double> candidates;

        if (gapRatio <= 20) {
            // 小缺口：从 3x~min(20x, maxAllowed) 随机选
            double minRate = 3.0;
            double maxRate = Math.min(20.0, maxAllowedMultiplier);

            if (maxRate < minRate) {
                // 连3倍都覆盖不了80%，这次不给大奖
                log("[V5-追赶] 缺口太小(gapRatio={:.2f})，不给大奖", gapRatio);
                return 0.0;
            }

            candidates = sortedRates.stream()
                    .filter(r -> r >= minRate && r <= maxRate)
                    .sorted()
                    .collect(Collectors.toList());

            log("[V5-追赶] 小缺口模式，候选: 3x~{:.2f}x，共{}个", maxRate, candidates.size());
        } else {
            // 大缺口：从 20x~min(gapRatio×0.8, maxAvailable) 随机选
            double minRate = 20.0;
            double maxRate = Math.min(maxAllowedMultiplier, sortedRates.get(sortedRates.size() - 1));

            candidates = sortedRates.stream()
                    .filter(r -> r >= minRate && r <= maxRate)
                    .sorted()
                    .collect(Collectors.toList());

            log("[V5-追赶] 大缺口模式，候选: 20x~{:.2f}x，共{}个", maxRate, candidates.size());
        }

        // 随机选择一个候选倍率
        if (candidates.isEmpty()) {
            log("[V5-追赶] 无合适候选倍率，返回0（不中）");
            return 0.0;
        }

        Double selected = candidates.get(random.nextInt(candidates.size()));
        log("[V5-追赶] 随机选中倍率:{}", selected);
        return selected;
    }

         /**
          * V5: 从Top N大奖中随机选择（保证随机性）
          */
         private Double selectRandomTopBigWin(int topN) {
             // 获取所有大奖并降序排列
            List<Double> bigRates = sortedRates.stream()
                    .filter(r -> r > BIG_WIN_THRESHOLD)
                    .sorted(Comparator.reverseOrder())
                    .toList();

            if (bigRates.isEmpty()) {
                return 50.0;
            }

            int actualN = Math.min(topN, bigRates.size());
            List<Double> topRates = bigRates.subList(0, actualN);

            // 从Top N中随机选择
            return topRates.get(random.nextInt(topRates.size()));
        }

        /**
         * V5: 选择小奖（低倍率 1-5x）
         */
        private Double selectSmallWinMultiplier(BigDecimal playerBet) {
            // 小奖范围: 1x - 5x (更低的小奖)
            List<Double> tinyRates = sortedRates.stream()
                    .filter(r -> r > 0 && r <= 5.0)
                    .sorted()
                    .toList();

            if (!tinyRates.isEmpty()) {
                // 优先选择较小的倍率
                return tinyRates.get(random.nextInt(Math.min(3, tinyRates.size())));
            }

            // 如果没有1-5x的，找最小的
            List<Double> smallRates = sortedRates.stream()
                    .filter(r -> r > 0 && r <= BIG_WIN_THRESHOLD)
                    .sorted()
                    .toList();

            return smallRates.isEmpty() ? 1.0 : smallRates.get(0);
        }

        /**
         * V5: 正目标模式下的小奖（让玩家开心但不亏本）
         * 范围: 1x - 3x，保持系统盈利
         */
        private Double selectPositiveTargetSmallWin(BigDecimal playerBet) {
            // 正目标模式下，给玩家小额奖金，保持系统盈利
            List<Double> safeRates = sortedRates.stream()
                    .filter(r -> r > 0 && r <= 3.0)  // 1-3x小额奖金
                    .sorted()
                    .toList();

            if (!safeRates.isEmpty()) {
                // 随机选择1-3x
                return safeRates.get(random.nextInt(Math.min(3, safeRates.size())));
            }

            // 如果没有1-3x的，返回1x
            return 1.0;
        }

        /**
         * 动态中奖决策（无预规划时使用）
         */
        private Double dynamicWinDecision(BigDecimal playerBet) {
            BigDecimal gap = calculateProfitGap();
            int remainingGames = targetGames - gamesPlayed + 1;
            int remainingWins = totalWinsPlanned - winCount;

            if (remainingWins <= 0) return 0.0;

            // gap > 0 表示系统还没达到目标（对于负目标意味着还没输够）
            boolean needPayout = (targetSystemProfit.compareTo(BigDecimal.ZERO) < 0 && gap.compareTo(BigDecimal.ZERO) > 0)
                    || (targetSystemProfit.compareTo(BigDecimal.ZERO) >= 0 && gap.compareTo(BigDecimal.ZERO) < 0);

            if (!needPayout) {
                // 系统已达标，不中奖
                return 0.0;
            }

            // 系统需要派奖
            double winProb = 0.4 + Math.min(0.3, gap.abs().divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP).doubleValue());
            winProb = Math.max(0.3, Math.min(0.9, winProb));

            if (random.nextDouble() < winProb) {
                boolean isBigWin = (bigWinCount < bigWinsPlanned) && random.nextDouble() < 0.5;
                return selectMultiplierForBet(isBigWin, playerBet);
            }

            return 0.0;
        }

        /**
         * 选择倍率 - 根据目标平均派奖选择最合适的倍率
         */
        private Double selectMultiplierForBet(boolean isBigWin, BigDecimal playerBet) {
            // 计算达到目标所需的平均倍率
            // 目标派奖 = totalBet - targetProfit
            // 需要平均每中奖 = 目标派奖 / 计划中奖数

            List<Double> bigRates = sortedRates.stream().filter(r -> r > BIG_WIN_THRESHOLD).sorted().toList();
            List<Double> smallRates = sortedRates.stream().filter(r -> r > 0 && r <= BIG_WIN_THRESHOLD).sorted().toList();

            if (bigRates.isEmpty() && smallRates.isEmpty()) return 0.0;

            List<Double> rates = isBigWin ? bigRates : smallRates;
            if (rates.isEmpty()) {
                rates = isBigWin ? smallRates : bigRates;
                if (rates.isEmpty()) return 0.0;
            }

            // 计算目标平均倍率
            double targetAvgMultiplier = calculateTargetAvgMultiplier(playerBet);

            return selectMultiplierByTargetAvg(rates, playerBet, isBigWin, targetAvgMultiplier);
        }

        /**
         * 计算达到目标所需的平均倍率
         */
        private double calculateTargetAvgMultiplier(BigDecimal playerBet) {
            // 需要总派奖
            BigDecimal requiredPayout = totalBet.subtract(targetSystemProfit);

            // 剩余需要派奖 = requiredPayout - 已派奖
            BigDecimal remainingPayout = requiredPayout.subtract(totalPayout);
            if (remainingPayout.compareTo(BigDecimal.ZERO) < 0) {
                remainingPayout = BigDecimal.ZERO;
            }

            // 剩余中奖机会
            int remainingWins = totalWinsPlanned - winCount;
            if (remainingWins <= 0) {
                return 0.0;  // 没有剩余中奖机会
            }

            // 平均每次中奖需要的派奖
            BigDecimal avgPayoutPerWin = remainingPayout.divide(new BigDecimal(remainingWins), MULTIPLIER_SCALE, RoundingMode.HALF_UP);

            // 平均倍率 = 平均派奖 / 当前bet
            double avgMultiplier = avgPayoutPerWin.divide(playerBet, MULTIPLIER_SCALE, RoundingMode.HALF_UP).doubleValue();

            return Math.max(0, avgMultiplier);
        }

        /**
         * 根据目标所需的平均派奖选择倍率
         */
        private Double selectMultiplierByTargetAvg(List<Double> rates, BigDecimal playerBet, boolean isBigWin, double targetAvgMultiplier) {
            // 在rates中找到最接近targetAvgMultiplier的倍率
            if (targetAvgMultiplier <= 0) {
                return rates.get(0);  // 最小的
            }

            if (isBigWin) {
                // 大奖需要 > 20, 选择接近targetAvgMultiplier的最小大奖
                List<Double> candidates = rates.stream()
                        .filter(r -> r >= 20.0 && r >= targetAvgMultiplier * 0.8 && r <= targetAvgMultiplier * 1.2)
                        .sorted()
                        .toList();

                if (!candidates.isEmpty()) {
                    return candidates.get(0);  // 选择接近且足够的最小值
                }

                // 如果没有合适范围，选一个安全的中间大奖 (25-40x)
                List<Double> safe = rates.stream().filter(r -> r >= 25.0 && r <= 40.0).sorted().toList();
                if (!safe.isEmpty()) {
                    return safe.get(safe.size() / 2);
                }

                return rates.get(0);  // fallback
            } else {
                // 小奖 <= 20, 选择接近targetAvgMultiplier的小奖
                List<Double> candidates = rates.stream()
                        .filter(r -> r <= 20.0 && r >= targetAvgMultiplier * 0.8 && r <= targetAvgMultiplier * 1.2)
                        .sorted()
                        .toList();

                if (!candidates.isEmpty()) {
                    // 选择接近targetAvgMultiplier的中间值
                    return candidates.get(candidates.size() / 2);
                }

                // 如果没有合适范围，选一个安全的小奖 (5-12x)
                List<Double> safe = rates.stream().filter(r -> r >= 5.0 && r <= 12.0).sorted().toList();
                if (!safe.isEmpty()) {
                    return safe.get(safe.size() / 2);
                }

                return rates.get(rates.size() / 2);  // fallback
            }
        }

        /**
         * 选择最小的可能倍率（当无需派奖时）
         */
        private Double selectSmallestPossible(boolean isBigWin) {
            List<Double> bigRates = sortedRates.stream().filter(r -> r > BIG_WIN_THRESHOLD).sorted().toList();
            List<Double> smallRates = sortedRates.stream().filter(r -> r > 0 && r <= BIG_WIN_THRESHOLD).sorted().toList();

            if (isBigWin && !bigRates.isEmpty()) {
                return bigRates.get(0);
            } else if (!isBigWin && !smallRates.isEmpty()) {
                return smallRates.get(0);
            } else if (!bigRates.isEmpty()) {
                return bigRates.get(0);
            } else if (!smallRates.isEmpty()) {
                return smallRates.get(0);
            }
            return 0.0;
        }

        /**
         * INTENSE 模式：选择极端倍率
         */
        private Double selectIntenseMultiplier(List<Double> rates, boolean isBigWin) {
            if (rates.isEmpty()) return 0.0;

            if (isBigWin) {
                // 大奖：两极分化 - 超大(50x+)或刚过线(20-30x)
                List<Double> extremeRates = rates.stream().filter(r -> r >= 50.0).sorted(Comparator.reverseOrder()).toList();
                List<Double> lowRates = rates.stream().filter(r -> r > 20.0 && r <= 30.0).sorted().toList();

                if (!extremeRates.isEmpty() && random.nextDouble() < 0.6) {
                    return extremeRates.get(random.nextInt(Math.min(3, extremeRates.size())));
                } else if (!lowRates.isEmpty()) {
                    return lowRates.get(random.nextInt(lowRates.size()));
                }
            } else {
                // 小奖：两极 - 极小(1-3x)或较大(10-20x)
                List<Double> lowRates = rates.stream().filter(r -> r <= 3.0).sorted().toList();
                List<Double> highSmallRates = rates.stream().filter(r -> r >= 10.0).sorted(Comparator.reverseOrder()).toList();

                if (!lowRates.isEmpty() && random.nextDouble() < 0.5) {
                    return lowRates.get(random.nextInt(lowRates.size()));
                } else if (!highSmallRates.isEmpty()) {
                    return highSmallRates.get(random.nextInt(highSmallRates.size()));
                }
            }

            return rates.get(random.nextInt(rates.size()));
        }

        /**
         * GENTLE 模式：选择中间倍率
         */
        private Double selectGentleMultiplier(List<Double> rates) {
            if (rates.isEmpty()) return 0.0;

            int midStart = rates.size() / 4;
            int midEnd = (rates.size() * 3) / 4;
            if (midStart >= midEnd) {
                midStart = 0;
                midEnd = rates.size();
            }

            List<Double> midRates = rates.subList(midStart, midEnd);
            return midRates.get(random.nextInt(midRates.size()));
        }

        /**
         * 更新分散性追踪
         */
        private void updateDispersionTracking(Double multiplier, int currentGameIndex) {
            if (multiplier != null && multiplier > 0) {
                String winType = multiplier > BIG_WIN_THRESHOLD ? "BIG" : "SMALL";
                consecutiveWins++;

                if ("BIG".equals(winType)) {
                    consecutiveBigWins++;
                    lastBigWinGame = currentGameIndex;
                } else {
                    consecutiveBigWins = 0;
                }

                lastWinGame = currentGameIndex;
                lastWinType = winType;
            } else {
                consecutiveWins = 0;
                consecutiveBigWins = 0;
                lastWinType = null;
            }
        }

        private BigDecimal calculateProfitGap() {
            return totalBet.subtract(totalPayout).subtract(targetSystemProfit);
        }

        public ControllerState snapshot() {
            return new ControllerState(gamesPlayed, winCount, totalBet, totalPayout);
        }

        public static Controller fromSnapshot(ControllerState s, int tg, BigDecimal tsp,
                                              Map<Double, Integer> rwm, VibeType vt, List<BigDecimal> betList) {
            log("[V4] 从快照恢复 | played:{} | wins:{} | bet:{} | payout:{}",
                    s.gamesPlayed, s.winCount, s.totalBet, s.totalPayout);
            return new Controller(tg, tsp, rwm, vt, betList, s.gamesPlayed, s.winCount, s.totalBet, s.totalPayout);
        }

        public BigDecimal getSystemProfit() {
            return totalBet.subtract(totalPayout);
        }

        // ========== 统计方法 ==========
        public int getActualWinCount() { return winCount; }
        public int getActualBigWinCount() { return bigWinCount; }
        public int getActualSmallWinCount() { return winCount - bigWinCount; }
        public double getActualWinRate() { return gamesPlayed > 0 ? (double) winCount / gamesPlayed : 0.0; }
        public double getBigWinRatio() { return winCount > 0 ? (double) bigWinCount / winCount : 0.0; }

        // ========== 波动性验证方法 ==========
        public double getBigWinStdDev() {
            if (bigWinMultipliers.size() <= 1) return 0.0;
            double mean = bigWinMultipliers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = bigWinMultipliers.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
            return Math.sqrt(variance);
        }

        public double getSmallWinStdDev() {
            if (smallWinMultipliers.size() <= 1) return 0.0;
            double mean = smallWinMultipliers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = smallWinMultipliers.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
            return Math.sqrt(variance);
        }

        public boolean isDispersionValid() {
            return consecutiveWins <= 2 && consecutiveBigWins <= 1;
        }
    }

    @Data
    public static class ControllerState {
        private final int gamesPlayed;
        private final int winCount;
        private final BigDecimal totalBet;
        private final BigDecimal totalPayout;
        public ControllerState(int g, int w, BigDecimal b, BigDecimal p) {
            gamesPlayed=g; winCount=w; totalBet=b; totalPayout=p;
        }
        @Override public String toString() {
            return String.format("State{games=%d, wins=%d, bet=%s, payout=%s}", gamesPlayed, winCount, totalBet, totalPayout);
        }
    }

    // ==================== 测试入口 ====================
    public static void main(String[] args) throws JsonProcessingException, UnsupportedEncodingException {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));

        ObjectMapper mapper = new ObjectMapper();
        String str = """
                {"0.0":1,"0.3":3964,"0.5":3070,"0.6":1267,"0.8":167,"0.9":479,"1.0":3043,"1.1":83,"1.2":148,"1.3":175,"1.4":40,"1.5":856,"1.6":61,"1.7":17,"1.8":76,"1.9":32,"2.0":1907,"2.1":40,"2.2":6,"2.3":114,"2.4":13,"2.5":357,"2.6":30,"2.7":35,"2.8":25,"2.9":9,"3.0":581,"3.1":19,"3.2":11,"3.3":28,"3.5":108,"3.6":12,"3.8":8,"3.9":4,"4.0":467,"4.3":17,"4.4":1,"4.5":38,"4.6":6,"4.8":6,"4.9":8,"5.0":207,"5.3":8,"5.5":1,"5.6":1,"5.7":3,"5.8":1,"6.0":40,"6.1":3,"6.2":4,"6.3":1,"6.5":1,"6.6":2,"6.9":1,"7.0":4,"7.3":2,"7.5":1,"7.6":1,"7.9":1,"8.0":21,"8.3":1,"8.9":1,"9.0":2,"10.0":552,"10.3":16,"10.5":17,"10.6":1,"10.9":2,"11.0":16,"11.2":1,"11.4":1,"11.5":1,"11.7":1,"11.9":2,"12.0":9,"12.1":1,"12.5":1,"12.6":1,"13.0":1,"14.0":2,"14.6":1,"14.9":1,"15.0":25,"15.6":1,"16.0":2,"17.0":1,"20.0":199,"20.3":1,"20.5":1,"20.6":1,"21.0":1,"21.2":1,"21.5":1,"22.0":1,"22.5":1,"23.3":1,"24.0":1,"24.3":1,"24.5":1,"25.0":1,"25.3":1,"25.5":1,"26.0":1,"26.5":1,"27.0":1,"30.0":99,"30.3":1,"30.5":1,"30.6":1,"31.0":1,"31.5":1,"32.0":1,"35.0":1,"35.3":1,"40.0":6,"40.3":1,"40.5":1,"41.0":1,"45.0":1,"50.0":94,"50.3":1,"50.6":1,"50.9":1,"51.0":1,"55.0":1,"60.0":14,"60.5":1,"70.0":1,"70.9":1,"81.3":1,"110.0":1}
                """;
        Map<Double, Integer> rates = mapper.readValue(str, new TypeReference<Map<Double, Integer>>() {});

        int n = 30;

        // ========== 玩家赢场景测试 (目标为负) ==========
        // 关键：目标必须数学上可达
        // 可达条件：总投注 + |目标| <= 总投注 × 最大倍率
        // 即：目标绝对值 <= 总投注 × (最大倍率 - 1)

        // TC-01: bet=4, n=30, 总投注=120, 目标=-300 (很容易达成)
        System.out.println("\n" + "═".repeat(70));
        System.out.println("║ TC-01: 恒定投注 bet=4, target=-300 (玩家赢)");
        System.out.println("═".repeat(70));
        runTestConstant(n, new BigDecimal("-300"), rates, VibeType.INTENSE, 4);

        // TC-02: bet=4, n=30, 总投注=120, 目标=-400 (可达)
        System.out.println("\n" + "═".repeat(70));
        System.out.println("║ TC-02: 恒定投注 bet=4, target=-400 (玩家赢)");
        System.out.println("═".repeat(70));
        runTestConstant(n, new BigDecimal("-400"), rates, VibeType.INTENSE, 4);

        // TC-03: bet=10, n=30, 总投注=300, 目标=-800 (可达)
        System.out.println("\n" + "═".repeat(70));
        System.out.println("║ TC-03: 恒定投注 bet=10, target=-800 (玩家赢)");
        System.out.println("═".repeat(70));
        runTestConstant(n, new BigDecimal("-800"), rates, VibeType.INTENSE, 10);

        // TC-04: bet=10, n=30, 总投注=300, 目标=-1000 (可达)
        System.out.println("\n" + "═".repeat(70));
        System.out.println("║ TC-04: 恒定投注 bet=10, target=-1000 (玩家赢)");
        System.out.println("═".repeat(70));
        runTestConstant(n, new BigDecimal("-1000"), rates, VibeType.INTENSE, 10);

        // TC-05: bet=10, n=30, target=-1500 (边界)
        System.out.println("\n" + "═".repeat(70));
        System.out.println("║ TC-05: 恒定投注 bet=10, target=-1500 (玩家赢)");
        System.out.println("═".repeat(70));
        runTestConstant(n, new BigDecimal("-1500"), rates, VibeType.INTENSE, 10);
    }

    private static void runTestSegmented(int n, BigDecimal target, Map<Double, Integer> rates,
                                        VibeType vibe, int betAmount, boolean segmentedMode) {
        List<BigDecimal> betList = new ArrayList<>();
        for (int i = 0; i < n; i++) betList.add(new BigDecimal(betAmount));

        // 使用新构造函数，指定分段参数
        Controller c = new Controller(n, target, rates, vibe, betList, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                0.10, segmentedMode, 0.20, 1.0);
        List<Double> multipliers = new ArrayList<>();
        List<BigDecimal> profits = new ArrayList<>();

        int actualGamesPlayed = 0;
        for (int i = 0; i < n; i++) {
            Double mult = c.calculateMultiplier(betList.get(i));
            multipliers.add(mult);
            profits.add(c.getSystemProfit());
            actualGamesPlayed++;

            // 达到80%%局数且目标已达成，才能停止
            BigDecimal profit = c.getSystemProfit();
            boolean achieved = isTargetAchieved(profit, target);
            if (achieved && i >= n * 0.80) {
                log("[V5] 目标达成且已达80%%局数，停止投注");
                break;
            }
        }

        printResult(c, multipliers, profits, target, actualGamesPlayed);
    }

    // 辅助方法：判断目标是否达成
    private static boolean isTargetAchieved(BigDecimal profit, BigDecimal target) {
        if (target.compareTo(BigDecimal.ZERO) < 0) {
            // 负目标（系统输）：盈利 <= 目标（如-5500 <= -5000）
            return profit.compareTo(target) <= 0;
        } else {
            // 正目标（系统赢）：盈利 >= 目标（如1300 >= 1200）
            return profit.compareTo(target) >= 0;
        }
    }

    private static void runTestConstant(int n, BigDecimal target, Map<Double, Integer> rates,
                                         VibeType vibe, int betAmount) {
        List<BigDecimal> betList = new ArrayList<>();
        for (int i = 0; i < n; i++) betList.add(new BigDecimal(betAmount));

        Controller c = new Controller(n, target, rates, vibe, betList, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        List<Double> multipliers = new ArrayList<>();
        List<BigDecimal> profits = new ArrayList<>();

        int actualGamesPlayed = 0;
        for (int i = 0; i < n; i++) {
            Double mult = c.calculateMultiplier(betList.get(i));
            multipliers.add(mult);
            profits.add(c.getSystemProfit());
            actualGamesPlayed++;

            // 达到80%%局数且目标已达成，才能停止
            BigDecimal profit = c.getSystemProfit();
            boolean achieved = isTargetAchieved(profit, target);
            if (achieved && i >= n * 0.80) {
                log("[V5] 目标达成且已达80%%局数，停止投注");
                break;
            }
        }

        printResult(c, multipliers, profits, target, actualGamesPlayed);
    }

    private static void runTestVariableBet(int n, BigDecimal target, Map<Double, Integer> rates,
                                            VibeType vibe, boolean ascending) {
        List<BigDecimal> betList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            betList.add(ascending
                    ? (i < n / 2 ? new BigDecimal("4") : new BigDecimal("500"))
                    : (i < n / 2 ? new BigDecimal("500") : new BigDecimal("4")));
        }

        Controller c = new Controller(n, target, rates, vibe, betList, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        List<Double> multipliers = new ArrayList<>();
        List<BigDecimal> profits = new ArrayList<>();

        int actualGamesPlayed = 0;
        for (int i = 0; i < n; i++) {
            Double mult = c.calculateMultiplier(betList.get(i));
            multipliers.add(mult);
            profits.add(c.getSystemProfit());
            actualGamesPlayed++;

            // 达到80%%局数且目标已达成，才能停止
            BigDecimal profit = c.getSystemProfit();
            boolean achieved = isTargetAchieved(profit, target);
            if (achieved && i >= n * 0.80) {
                log("[V5] 目标达成且已达80%%局数，停止投注");
                break;
            }
        }

        printResult(c, multipliers, profits, target, actualGamesPlayed);
    }

    private static void printResult(Controller c, List<Double> multipliers, List<BigDecimal> profits, BigDecimal target) {
        int totalWins = c.getActualWinCount();
        int bigWins = c.getActualBigWinCount();
        int smallWins = c.getActualSmallWinCount();
        int n = c.getTargetGames();
        double winRate = (double) totalWins / n;
        double bigWinRatio = totalWins > 0 ? (double) bigWins / totalWins : 0.0;

        System.out.println("\n--- 中奖明细 (前15局) ---");
        for (int i = 0; i < Math.min(15, n); i++) {
            Double mult = multipliers.get(i);
            String winType = mult > 20 ? "【大奖】" : (mult > 0 ? "【小奖】" : "  未中  ");
            System.out.printf("  第%2d局 | 倍率:%6.2f | 盈利:%10.2f %s%n", i + 1, mult, profits.get(i), winType);
        }
        if (n > 15) System.out.println("  ... (后" + (n - 15) + "局省略)");

        System.out.println("\n--- 统计结果 ---");
        System.out.printf("  中奖率: %d/%d (%.1f%%)%n", totalWins, n, winRate * 100);
        System.out.printf("  大奖: %d 局 | 小奖: %d 局%n", bigWins, smallWins);
        System.out.printf("  大奖占比: %.1f%%%n", bigWinRatio * 100);
        System.out.printf("  大奖倍率σ: %.2f | 小奖倍率σ: %.2f%n", c.getBigWinStdDev(), c.getSmallWinStdDev());

        BigDecimal actualProfit = c.getSystemProfit();
        double deviation = target.abs().compareTo(BigDecimal.ZERO) > 0
                ? actualProfit.subtract(target).abs().divide(target.abs(), 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;

        System.out.printf("  系统盈利: %s (目标: %s)%n", actualProfit.setScale(2, RoundingMode.HALF_UP), target);
        System.out.printf("  偏差: %.2f%%%n", deviation);

        // 验收判定
        boolean winRateOK = winRate <= 0.60;
        boolean bigWinRatioOK = totalWins == 0 || (bigWinRatio >= 0.40 && bigWinRatio <= 0.60);
        boolean deviationOK = deviation <= 10.0;
        // 目标方向：对于负目标(系统输)，盈利应为负；对于正目标(系统赢)，盈利应为正
        boolean targetDirectionOK = (target.compareTo(BigDecimal.ZERO) < 0 && actualProfit.compareTo(BigDecimal.ZERO) < 0)
                || (target.compareTo(BigDecimal.ZERO) > 0 && actualProfit.compareTo(BigDecimal.ZERO) > 0)
                || (target.compareTo(BigDecimal.ZERO) == 0 && actualProfit.compareTo(BigDecimal.ZERO) == 0);

        boolean intenseBigOK = c.getBigWinStdDev() > 15;
        boolean intenseSmallOK = c.getSmallWinStdDev() > 3;
        boolean gentleBigOK = c.getBigWinStdDev() < 8;
        boolean gentleSmallOK = c.getSmallWinStdDev() < 2;
        boolean vibeMatch = c.getVibeType() == VibeType.INTENSE
                ? (intenseBigOK && intenseSmallOK)
                : (gentleBigOK && gentleSmallOK);

        System.out.println("\n--- 验收结果 ---");
        System.out.printf("  [%s] 中奖率≤60%% (%.1f%%)%n", winRateOK ? "✓" : "✗", winRate * 100);
        System.out.printf("  [%s] 大奖占比40-60%% (%.1f%%)%n", bigWinRatioOK ? "✓" : "✗", bigWinRatio * 100);
        System.out.printf("  [%s] 偏差≤10%% (%.2f%%)%n", deviationOK ? "✓" : "✗", deviation);
        System.out.printf("  [%s] 目标方向正确 (盈利:%s 目标:%s)%n", targetDirectionOK ? "✓" : "✗", actualProfit, target);
        System.out.printf("  [%s] 波动性符合%s%n", vibeMatch ? "✓" : "✗", c.getVibeType());

        boolean allPassed = winRateOK && bigWinRatioOK && deviationOK && targetDirectionOK;
        System.out.printf("%n  【%s】所有验收项通过%n", allPassed ? "✓ PASS" : "✗ FAIL");
    }

    private static void printResult(Controller c, List<Double> multipliers, List<BigDecimal> profits, BigDecimal target, int actualGamesPlayed) {
        int totalWins = c.getActualWinCount();
        int bigWins = c.getActualBigWinCount();
        int smallWins = c.getActualSmallWinCount();
        int n = c.getTargetGames();
        double winRate = n > 0 ? (double) totalWins / n : 0.0;
        double bigWinRatio = totalWins > 0 ? (double) bigWins / totalWins : 0.0;

        System.out.println("\n--- 中奖明细 (前20局或实际局数) ---");
        int displayLimit = Math.min(Math.max(20, actualGamesPlayed), n);
        for (int i = 0; i < displayLimit; i++) {
            if (i >= multipliers.size()) break;
            Double mult = multipliers.get(i);
            String winType = mult > 20 ? "【大奖】" : (mult > 0 ? "【小奖】" : "  未中  ");
            String prefix = (i == actualGamesPlayed - 1) ? ">>>" : "   ";
            System.out.printf("%s 第%2d局 | 倍率:%6.2f | 盈利:%10.2f %s%n", prefix, i + 1, mult, profits.get(i), winType);
        }
        if (actualGamesPlayed < n) {
            System.out.printf("   ... (第%d局后停止投注，目标已达成)%n", actualGamesPlayed);
        }

        System.out.println("\n--- 统计结果 ---");
        System.out.printf("  实际游戏局数: %d/%d%n", actualGamesPlayed, n);
        System.out.printf("  中奖率: %d/%d (%.1f%%)%n", totalWins, actualGamesPlayed, actualGamesPlayed > 0 ? (double) totalWins / actualGamesPlayed * 100 : 0);
        System.out.printf("  大奖: %d 局 | 小奖: %d 局%n", bigWins, smallWins);
        System.out.printf("  大奖占比: %.1f%%%n", bigWinRatio * 100);
        System.out.printf("  大奖倍率σ: %.2f | 小奖倍率σ: %.2f%n", c.getBigWinStdDev(), c.getSmallWinStdDev());

        BigDecimal actualProfit = c.getSystemProfit();
        double deviation = target.abs().compareTo(BigDecimal.ZERO) > 0
                ? actualProfit.subtract(target).abs().divide(target.abs(), 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;

        System.out.printf("  系统盈利: %s (目标: %s)%n", actualProfit.setScale(2, RoundingMode.HALF_UP), target);
        System.out.printf("  偏差: %.2f%%%n", deviation);

        // V5 特殊验收
        boolean earlyCompletion = actualGamesPlayed < n;
        System.out.printf("  提前完成: %s%n", earlyCompletion ? "✓ 是 (第" + actualGamesPlayed + "局完成)" : "否");

        // 验收判定
        boolean winRateOK = actualGamesPlayed > 0 && (double) totalWins / actualGamesPlayed <= 0.60;
        boolean bigWinRatioOK = totalWins == 0 || (bigWinRatio >= 0.40 && bigWinRatio <= 0.60);
        boolean deviationOK = deviation <= 10.0;
        // 目标方向：对于负目标(系统输)，盈利应为负；对于正目标(系统赢)，盈利应为正
        boolean targetDirectionOK = (target.compareTo(BigDecimal.ZERO) < 0 && actualProfit.compareTo(BigDecimal.ZERO) < 0)
                || (target.compareTo(BigDecimal.ZERO) > 0 && actualProfit.compareTo(BigDecimal.ZERO) > 0)
                || (target.compareTo(BigDecimal.ZERO) == 0 && actualProfit.compareTo(BigDecimal.ZERO) == 0);

        boolean intenseBigOK = c.getBigWinStdDev() > 15;
        boolean intenseSmallOK = c.getSmallWinStdDev() > 3;
        boolean gentleBigOK = c.getBigWinStdDev() < 8;
        boolean gentleSmallOK = c.getSmallWinStdDev() < 2;
        boolean vibeMatch = c.getVibeType() == VibeType.INTENSE
                ? (intenseBigOK && intenseSmallOK)
                : (gentleBigOK && gentleSmallOK);

        System.out.println("\n--- 验收结果 ---");
        System.out.printf("  [%s] 中奖率≤60%% (%.1f%%)%n", winRateOK ? "✓" : "✗", actualGamesPlayed > 0 ? (double) totalWins / actualGamesPlayed * 100 : 0);
        System.out.printf("  [%s] 大奖占比40-60%% (%.1f%%)%n", bigWinRatioOK ? "✓" : "✗", bigWinRatio * 100);
        System.out.printf("  [%s] 偏差≤10%% (%.2f%%)%n", deviationOK ? "✓" : "✗", deviation);
        System.out.printf("  [%s] 目标方向正确 (盈利:%s 目标:%s)%n", targetDirectionOK ? "✓" : "✗", actualProfit, target);
        System.out.printf("  [%s] 波动性符合%s%n", vibeMatch ? "✓" : "✗", c.getVibeType());

        boolean allPassed = winRateOK && bigWinRatioOK && deviationOK && targetDirectionOK;
        System.out.printf("%n  【%s】所有验收项通过%n", allPassed ? "✓ PASS" : "✗ FAIL");
    }
}
