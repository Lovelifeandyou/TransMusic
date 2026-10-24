using System.Diagnostics;

namespace TransMusic.Core;

/// <summary>队列完成后的一次性统计。</summary>
public sealed record QueueStats(int Total, int Ok, int Failed, int Skipped, int Canceled, TimeSpan Elapsed);

/// <summary>
/// 批量转换队列：并发 N 个任务（信号量限流），进度与状态通过事件上报，支持整体取消。
///
/// 线程模型：
///   · 所有对 <see cref="Job"/> 的写入都经过构造时传入的 <c>marshal</c>（界面线程调度器）。
///     WinUI/WPF 不允许后台线程改绑定对象，否则绑定的 PropertyChanged 会抛跨线程异常。
///   · 任务结果的计数由队列内部用 Interlocked 完成，不依赖「界面上的状态是否已经刷新」，
///     避免「界面还没收到最后一次更新就开始统计」的竞态。
///   传 null 时同步执行（控制台/测试用）。
/// </summary>
public sealed class ConvertQueue
{
    private readonly Action<Action>? _marshal;
    private readonly object _finishLock = new();
    private readonly HashSet<int> _finishedJobs = new();

    private int _ok;
    private int _failed;
    private int _skipped;
    private int _canceled;

    public ConvertQueue(Action<Action>? marshal = null)
    {
        _marshal = marshal;
    }

    public event Action<Job>? JobUpdated;
    public event Action<Job, double, string>? JobProgress;
    public event Action<string>? Log;
    public event Action<QueueStats>? Finished;

    public bool IsRunning { get; private set; }

    private CancellationTokenSource? _cts;

    public void Start(
        IReadOnlyList<Job> jobs,
        AudioFormat fmt,
        ConvertSettings settings,
        string? outputDir,
        string template = "{name}",
        string conflict = "rename",
        int workers = 2)
    {
        if (IsRunning || jobs.Count == 0) return;

        IsRunning = true;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;

        lock (_finishLock)
        {
            _finishedJobs.Clear();
            _ok = _failed = _skipped = _canceled = 0;
        }

        _ = Task.Run(() => RunCoreAsync(jobs, fmt, settings, outputDir, template, conflict,
            Math.Clamp(workers, 1, 16), token), CancellationToken.None);
    }

    public void Cancel()
    {
        try
        {
            _cts?.Cancel();
        }
        catch
        {
            // 已释放
        }
    }

    // ------------------------------------------------------------------ 线程编组
    private void Mutate(Job job, Action<Job> change)
    {
        void Apply()
        {
            change(job);
            job.RaiseAll();
            JobUpdated?.Invoke(job);
        }

        if (_marshal is null) Apply();
        else _marshal(Apply);
    }

    private void MutateProgress(Job job, double percent, string speed)
    {
        void Apply()
        {
            job.Progress = percent;
            job.Speed = speed;
            JobProgress?.Invoke(job, percent, speed);
        }

        if (_marshal is null) Apply();
        else _marshal(Apply);
    }

    /// <summary>结算一个任务（只会生效一次），并计入统计。</summary>
    private void Finish(Job job, JobStatus status, string message, double progress = -1)
    {
        lock (_finishLock)
        {
            if (!_finishedJobs.Add(job.Id)) return;
            switch (status)
            {
                case JobStatus.Done: _ok++; break;
                case JobStatus.Failed: _failed++; break;
                case JobStatus.Skipped: _skipped++; break;
                case JobStatus.Canceled: _canceled++; break;
            }
        }

        Mutate(job, j =>
        {
            j.Status = status;
            j.Message = message;
            if (progress >= 0) j.Progress = progress;
        });
    }

    private (int Ok, int Failed, int Skipped, int Canceled) Snapshot()
    {
        lock (_finishLock)
        {
            return (_ok, _failed, _skipped, _canceled);
        }
    }

    // ------------------------------------------------------------------ 主流程
    private async Task RunCoreAsync(
        IReadOnlyList<Job> jobs,
        AudioFormat fmt,
        ConvertSettings settings,
        string? outputDir,
        string template,
        string conflict,
        int workers,
        CancellationToken token)
    {
        var sw = Stopwatch.StartNew();
        foreach (var job in jobs)
        {
            Mutate(job, j =>
            {
                j.Format = fmt;
                j.Settings = settings;
                j.Status = JobStatus.Waiting;
                j.Progress = 0;
                j.Speed = string.Empty;
                j.Message = string.Empty;
                j.OutputPath = null;
            });
        }

        Log?.Invoke($"开始转换 {jobs.Count} 个文件 → {fmt.Label}（并发 {workers}）");

        using var semaphore = new SemaphoreSlim(workers, workers);
        var tasks = new List<Task>(jobs.Count);
        for (var i = 0; i < jobs.Count; i++)
        {
            var job = jobs[i];
            var index = i + 1;
            tasks.Add(Task.Run(async () =>
            {
                try
                {
                    await semaphore.WaitAsync(token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    Finish(job, JobStatus.Canceled, "已取消");
                    return;
                }

                try
                {
                    await RunOneAsync(job, fmt, settings, outputDir, template, conflict, index, token)
                        .ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    Finish(job, JobStatus.Canceled, "已取消");
                }
                catch (Exception ex)
                {
                    Finish(job, JobStatus.Failed, ex.Message);
                    Log?.Invoke($"✘ {job.FileName} 失败：{ex.Message}");
                }
                finally
                {
                    semaphore.Release();
                }
            }, CancellationToken.None));
        }

        try
        {
            await Task.WhenAll(tasks).ConfigureAwait(false);
        }
        catch
        {
            // 单个任务的异常已在内部处理
        }

        foreach (var job in jobs.Where(j => j.Status == JobStatus.Waiting))
        {
            Finish(job, JobStatus.Canceled, "已取消");
        }

        sw.Stop();
        var (ok, failed, skipped, canceled) = Snapshot();
        var stats = new QueueStats(jobs.Count, ok, failed, skipped, canceled, sw.Elapsed);

        IsRunning = false;
        Log?.Invoke($"全部结束：成功 {ok}，失败 {failed}，跳过 {skipped}，取消 {canceled}，用时 {sw.Elapsed.TotalSeconds:0.0} 秒");
        Finished?.Invoke(stats);
    }

    private async Task RunOneAsync(
        Job job,
        AudioFormat fmt,
        ConvertSettings settings,
        string? outputDir,
        string template,
        string conflict,
        int index,
        CancellationToken token)
    {
        var dst = OutputPathResolver.Resolve(job.SourcePath, outputDir, fmt, job.Info, template, conflict, index);
        if (dst is null)
        {
            Finish(job, JobStatus.Skipped, "目标文件已存在（跳过模式）");
            Log?.Invoke($"– {job.FileName}：目标已存在，跳过");
            return;
        }

        Mutate(job, j =>
        {
            j.Status = JobStatus.Running;
            j.OutputPath = dst;
            j.Progress = 0;
        });
        Log?.Invoke($"▶ {job.FileName} → {Path.GetFileName(dst)}");

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
        }
        catch (Exception ex)
        {
            Finish(job, JobStatus.Failed, $"无法创建输出目录：{ex.Message}");
            return;
        }

        try
        {
            var args = CommandBuilder.Build(job.SourcePath, dst, fmt, settings, job.Info);
            var result = await FfmpegRunner.RunAsync(
                args,
                job.Info.DurationSeconds,
                (percent, speed) => MutateProgress(job, percent, speed),
                token).ConfigureAwait(false);

            Mutate(job, j => j.Elapsed = result.Elapsed);

            if (result.Ok)
            {
                if (!File.Exists(dst) || new FileInfo(dst).Length == 0)
                {
                    Finish(job, JobStatus.Failed, "输出文件为空");
                }
                else
                {
                    Finish(job, JobStatus.Done, Path.GetFileName(dst), 100);
                    Log?.Invoke($"✔ {job.FileName} → {Path.GetFileName(dst)}（{new FileInfo(dst).Length / 1024.0 / 1024.0:0.00} MB，{result.Elapsed.TotalSeconds:0.0} 秒）");
                }
            }
            else if (result.Canceled)
            {
                TryDelete(dst);
                Finish(job, JobStatus.Canceled, "已取消");
                Log?.Invoke($"× {job.FileName} 已取消");
            }
            else
            {
                TryDelete(dst);
                Finish(job, JobStatus.Failed, result.Message);
                Log?.Invoke($"✘ {job.FileName} 失败：{result.Message}");
            }
        }
        catch (OperationCanceledException)
        {
            TryDelete(dst);
            Finish(job, JobStatus.Canceled, "已取消");
        }
        catch (Exception ex)
        {
            TryDelete(dst);
            Finish(job, JobStatus.Failed, ex.Message);
            Log?.Invoke($"✘ {job.FileName} 失败：{ex.Message}");
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 删除失败无所谓，下次会覆盖
        }
    }
}
