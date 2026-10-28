package prcheckout;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.TimeoutUtil;
import org.jetbrains.annotations.NotNull;

public final class CheckOutPullRequestAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        String input = Messages.showInputDialog(project, "Pull request number:", "Check Out Pull Request", null);
        if (input == null) return;
        String number = input.trim().replaceFirst("^#", "");
        if (!number.matches("\\d+")) return;

        new Task.Backgroundable(project, "Checking out pull request #" + number) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ProcessOutput output;
                try {
                    output = ExecUtil.execAndGetOutput(
                        new GeneralCommandLine("gh", "pr", "checkout", number).withWorkDirectory(project.getBasePath()));
                } catch (ExecutionException exception) {
                    ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(project, exception.getMessage(), "Could not run gh"));
                    return;
                }
                if (output.getExitCode() != 0) {
                    ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(project, output.getStderr(), "gh pr checkout #" + number + " failed"));
                    return;
                }

                VirtualFile firstChangedFile = null;
                try {
                    ProcessOutput changedFiles = ExecUtil.execAndGetOutput(
                        new GeneralCommandLine("gh", "pr", "diff", number, "--name-only").withWorkDirectory(project.getBasePath()));
                    for (String path : changedFiles.getStdoutLines()) {
                        firstChangedFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/" + path);
                        if (firstChangedFile != null) break;
                    }
                } catch (ExecutionException ignored) {
                }
                VirtualFile fileToOpen = firstChangedFile;

                indicator.setText("Waiting for IntelliJ to link the branch to pull request #" + number);
                ActionManager actionManager = ActionManager.getInstance();
                AnAction showAction = actionManager.getAction("Github.Pull.Request.Show.In.Toolwindow");
                AnAction reviewModeAction = actionManager.getAction("Github.Pull.Request.Review.In.Editor.Toggle");
                for (int attempt = 0; attempt < 60; attempt++) {
                    indicator.checkCanceled();
                    AnActionEvent showEvent = AnActionEvent.createEvent(showAction, SimpleDataContext.getProjectContext(project),
                        showAction.getTemplatePresentation().clone(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null);
                    AnActionEvent reviewModeEvent = AnActionEvent.createEvent(reviewModeAction, SimpleDataContext.getProjectContext(project),
                        reviewModeAction.getTemplatePresentation().clone(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null);
                    ReadAction.run(() -> {
                        ActionUtil.performDumbAwareUpdate(showAction, showEvent, false);
                        ActionUtil.performDumbAwareUpdate(reviewModeAction, reviewModeEvent, false);
                    });
                    if (showEvent.getPresentation().isEnabledAndVisible()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            ActionUtil.performActionDumbAwareWithCallbacks(showAction, showEvent);
                            if (reviewModeEvent.getPresentation().isEnabled() && !Toggleable.isSelected(reviewModeEvent.getPresentation())) {
                                ActionUtil.performActionDumbAwareWithCallbacks(reviewModeAction, reviewModeEvent);
                            }
                            if (fileToOpen != null) FileEditorManager.getInstance(project).openFile(fileToOpen, true);
                        });
                        return;
                    }
                    TimeoutUtil.sleep(1000);
                }
                ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showWarningDialog(project, "Checked out, but IntelliJ never linked the branch to an open pull request.",
                        "Pull request #" + number));
            }
        }.queue();
    }
}
