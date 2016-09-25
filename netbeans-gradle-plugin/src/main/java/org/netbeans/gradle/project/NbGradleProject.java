package org.netbeans.gradle.project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import org.jtrim.cancel.CancellationToken;
import org.jtrim.property.PropertySource;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.project.api.config.ProjectSettingsProvider;
import org.netbeans.gradle.project.api.task.BuiltInGradleCommandQuery;
import org.netbeans.gradle.project.api.task.GradleCommandExecutor;
import org.netbeans.gradle.project.extensions.ExtensionLoader;
import org.netbeans.gradle.project.extensions.NbGradleExtensionRef;
import org.netbeans.gradle.project.model.DefaultGradleModelLoader;
import org.netbeans.gradle.project.model.NbGradleModel;
import org.netbeans.gradle.project.model.SettingsGradleDef;
import org.netbeans.gradle.project.model.issue.ModelLoadIssue;
import org.netbeans.gradle.project.model.issue.ModelLoadIssueReporter;
import org.netbeans.gradle.project.properties.DefaultProjectSettingsProvider;
import org.netbeans.gradle.project.properties.GradleAuxiliaryConfiguration;
import org.netbeans.gradle.project.properties.GradleAuxiliaryProperties;
import org.netbeans.gradle.project.properties.GradleCustomizer;
import org.netbeans.gradle.project.properties.NbGradleCommonProperties;
import org.netbeans.gradle.project.properties.NbGradleSingleProjectConfigProvider;
import org.netbeans.gradle.project.properties.ProjectProfileLoader;
import org.netbeans.gradle.project.properties.ProjectPropertiesApi;
import org.netbeans.gradle.project.query.GradleSharabilityQuery;
import org.netbeans.gradle.project.query.GradleSourceEncodingQuery;
import org.netbeans.gradle.project.query.GradleTemplateAttrProvider;
import org.netbeans.gradle.project.tasks.DefaultGradleCommandExecutor;
import org.netbeans.gradle.project.tasks.MergedBuiltInGradleCommandQuery;
import org.netbeans.gradle.project.util.CloseableActionContainer;
import org.netbeans.gradle.project.util.NbFunction;
import org.netbeans.gradle.project.util.PathResolver;
import org.netbeans.gradle.project.view.GradleActionProvider;
import org.netbeans.gradle.project.view.GradleProjectLogicalViewProvider;
import org.netbeans.spi.project.ProjectState;
import org.netbeans.spi.project.ui.ProjectOpenedHook;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public final class NbGradleProject implements Project {
    private final String name;

    private final FileObject projectDir;
    private final File projectDirAsFile;
    private final Path projectDirAsPath;

    private final AtomicReference<ServiceObjects> serviceObjectsRef;

    private NbGradleProject(FileObject projectDir) throws IOException {
        this.projectDir = projectDir;
        this.projectDirAsFile = FileUtil.toFile(projectDir);
        if (projectDirAsFile == null) {
            throw new IOException("Project directory does not exist.");
        }
        this.projectDirAsPath = projectDirAsFile.toPath();

        this.name = projectDir.getNameExt();

        this.serviceObjectsRef = new AtomicReference<>(null);
    }

    private ServiceObjects initServiceObjects(ProjectState state) {
        ServiceObjects serviceObjects = new ServiceObjects(this, state);
        if (!serviceObjectsRef.compareAndSet(null, serviceObjects)) {
            throw new IllegalStateException("Alread initialized: ServiceObjects");
        }

        for (ProjectInitListener listener: serviceObjects.services.lookupAll(ProjectInitListener.class)) {
            listener.onInitProject();
        }
        return serviceObjects;
    }

    private ServiceObjects getServiceObjects() {
        ServiceObjects result = serviceObjectsRef.get();
        if (result == null) {
            throw new IllegalStateException("Services are not yet initialized.");
        }
        return result;
    }

    @Nonnull
    public static NbGradleProject createProject(FileObject projectDir, ProjectState state) throws IOException {
        NbGradleProject project = new NbGradleProject(projectDir);
        ServiceObjects serviceObjects = project.initServiceObjects(state);
        serviceObjects.updateExtensions(ExtensionLoader.loadExtensions(project));

        LoadedProjectManager.getDefault().addProject(project);
        project.updateSettingsFile();
        return project;
    }

    private SettingsFileManager getSettingsFileManager() {
        return getServiceObjects().settingsFileManager;
    }

    public SettingsGradleDef getPreferredSettingsGradleDef() {
        return getSettingsFileManager().getPreferredSettingsGradleDef();
    }

    public void updateSettingsFile() {
        getSettingsFileManager().updateSettingsFile();
    }

    @Nonnull
    public BuiltInGradleCommandQuery getMergedCommandQuery() {
        return getServiceObjects().mergedCommandQuery;
    }

    private ProjectModelManager getModelManager() {
        return getServiceObjects().modelManager;
    }

    private ProjectModelUpdater<NbGradleModel> getModelUpdater() {
        return getServiceObjects().modelUpdater;
    }

    public void ensureLoadRequested() {
        getModelUpdater().ensureLoadRequested();
    }

    public void reloadProject() {
        getModelUpdater().reloadProject();
    }

    public void waitForLoadedProject(CancellationToken cancelToken) {
        getModelUpdater().waitForLoadedProject(cancelToken);
    }

    public boolean tryWaitForLoadedProject(long timeout, TimeUnit unit) {
        return getModelUpdater().tryWaitForLoadedProject(timeout, unit);
    }

    public boolean tryWaitForLoadedProject(CancellationToken cancelToken, long timeout, TimeUnit unit) {
        return getModelUpdater().tryWaitForLoadedProject(cancelToken, timeout, unit);
    }

    public boolean isSameProject(Project other) {
        return Objects.equals(other.getProjectDirectory(), getProjectDirectory());
    }

    public NbGradleProjectExtensions getExtensions() {
        return getServiceObjects().extensions;
    }

    public NbGradleSingleProjectConfigProvider getConfigProvider() {
        return getServiceObjects().configProvider;
    }

    public ProjectProfileLoader getProfileLoader() {
        return getServiceObjects().profileLoader;
    }

    public void displayError(String errorText, Throwable exception) {
        if (!ModelLoadIssueReporter.reportIfBuildScriptError(this, exception)) {
            ModelLoadIssueReporter.reportAllIssues(errorText, Collections.singleton(
                    new ModelLoadIssue(this, null, null, null, exception)));
        }
    }

    public ProjectIssueManager getProjectIssueManager() {
        return getServiceObjects().projectIssueManager;
    }

    public PropertySource<NbGradleModel> currentModel() {
        return getModelManager().currentModel();
    }

    public ProjectSettingsProvider getProjectSettingsProvider() {
        return getServiceObjects().projectSettingsProvider;
    }

    public GradleCommandExecutor getGradleCommandExecutor() {
        return getServiceObjects().commandExecutor;
    }

    public NbGradleCommonProperties getCommonProperties() {
        return getServiceObjects().commonProperties;
    }

    @Nonnull
    public String getName() {
        return name;
    }

    public ProjectDisplayInfo getDisplayInfo() {
        return getServiceObjects().projectDisplayInfo;
    }

    public String getDisplayName() {
        return getDisplayInfo().displayName().getValue();
    }

    @Nonnull
    public Path getProjectDirectoryAsPath() {
        return projectDirAsPath;
    }

    @Nonnull
    public File getProjectDirectoryAsFile() {
        return projectDirAsFile;
    }

    @Override
    public FileObject getProjectDirectory() {
        return projectDir;
    }

    public void tryReplaceModel(NbGradleModel model) {
        if (getProjectDirectoryAsFile().equals(model.getProjectDir())) {
            getModelManager().updateModel(model, null);
        }
    }

    @Override
    public Lookup getLookup() {
        return getServiceObjects().projectLookups.getMainLookup();
    }

    // equals and hashCode is provided, so that NetBeans doesn't load the
    // same project multiple times.

    @Override
    public int hashCode() {
        return 201 + projectDir.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (getClass() != obj.getClass()) return false;

        final NbGradleProject other = (NbGradleProject)obj;
        return this.projectDir.equals(other.projectDir);
    }

    private static class OpenHook extends ProjectOpenedHook {
        private final NbGradleProject project;
        private final CloseableActionContainer closeableActions;
        private final AtomicBoolean initialized;

        public OpenHook(NbGradleProject project) {
            this.project = project;
            this.closeableActions = new CloseableActionContainer();
            this.initialized = new AtomicBoolean(false);
        }

        private void ensureInitialized() {
            if (!initialized.compareAndSet(false, true)) {
                return;
            }

            this.closeableActions.defineAction(ServiceObjects.LICENSE_MANAGER.getRegisterListenerAction(
                    project.currentModel(),
                    project.getCommonProperties().licenseHeaderInfo().getActiveSource()));

            this.closeableActions.defineAction(RootProjectRegistry.getDefault().forProject(project));
        }

        @Override
        protected void projectOpened() {
            ensureInitialized();

            closeableActions.open();
            project.getModelUpdater().reloadProjectMayUseCache();
        }

        @Override
        protected void projectClosed() {
            closeableActions.close();
        }
    }

    private static final class ServiceObjects {
        public static final LicenseManager<NbGradleModel> LICENSE_MANAGER = createLicenseManager();

        public final GradleAuxiliaryConfiguration auxConfig;
        public final NbGradleSingleProjectConfigProvider configProvider;
        public final ProjectProfileLoader profileLoader;
        public final NbGradleCommonProperties commonProperties;
        public final ProjectState state;
        public final GradleProjectInformation projectInformation;
        public final GradleProjectLogicalViewProvider logicalViewProvider;
        public final GradleActionProvider actionProvider;
        public final GradleSharabilityQuery sharabilityQuery;
        public final GradleSourceEncodingQuery sourceEncoding;
        public final GradleCustomizer customizer;
        public final GradleAuxiliaryProperties auxProperties;
        public final GradleTemplateAttrProvider templateAttrProvider;
        public final DefaultGradleCommandExecutor commandExecutor;
        public final ProjectIssueManager projectIssueManager;
        public final ProjectSettingsProvider projectSettingsProvider;
        public final ProjectModelManager modelManager;
        public final ProjectModelUpdater<NbGradleModel> modelUpdater;
        public final ProjectDisplayInfo projectDisplayInfo;
        public final BuiltInGradleCommandQuery mergedCommandQuery;
        public final SettingsFileManager settingsFileManager;

        public final Lookup services;
        public final NbGradleProjectLookups projectLookups;
        public final UpdatableProjectExtensions extensions;

        public ServiceObjects(NbGradleProject project, ProjectState state) {
            List<Object> serviceObjects = new LinkedList<>();
            serviceObjects.add(project);

            File projectDir = project.getProjectDirectoryAsFile();

            this.configProvider = add(
                    NbGradleSingleProjectConfigProvider.create(getSettingsDir(projectDir), project),
                    serviceObjects);
            this.profileLoader = new ProjectProfileLoader(configProvider);
            this.commonProperties = configProvider.getCommonProperties(configProvider.getActiveSettingsQuery());

            this.auxConfig = add(new GradleAuxiliaryConfiguration(profileLoader), serviceObjects);
            this.state = add(state, serviceObjects);
            this.projectInformation = add(new GradleProjectInformation(project), serviceObjects);
            this.logicalViewProvider = add(new GradleProjectLogicalViewProvider(project), serviceObjects);
            this.actionProvider = add(new GradleActionProvider(project), serviceObjects);
            this.sharabilityQuery = add(new GradleSharabilityQuery(project), serviceObjects);
            this.sourceEncoding = add(new GradleSourceEncodingQuery(project), serviceObjects);
            this.customizer = add(new GradleCustomizer(project), serviceObjects);
            this.auxProperties = add(new GradleAuxiliaryProperties(auxConfig), serviceObjects);
            this.templateAttrProvider = add(new GradleTemplateAttrProvider(project, LICENSE_MANAGER), serviceObjects);
            this.commandExecutor = add(new DefaultGradleCommandExecutor(project), serviceObjects);
            this.projectIssueManager = add(new ProjectIssueManager(), serviceObjects);
            this.projectSettingsProvider = add(new DefaultProjectSettingsProvider(project), serviceObjects);

            add(new OpenHook(project), serviceObjects);

            add(ProjectPropertiesApi.buildPlatform(commonProperties.targetPlatform().getActiveSource()), serviceObjects);
            add(ProjectPropertiesApi.scriptPlatform(commonProperties.scriptPlatform().getActiveSource()), serviceObjects);
            add(ProjectPropertiesApi.sourceEncoding(commonProperties.sourceEncoding().getActiveSource()), serviceObjects);
            add(ProjectPropertiesApi.sourceLevel(commonProperties.sourceLevel().getActiveSource()), serviceObjects);

            this.mergedCommandQuery = new MergedBuiltInGradleCommandQuery(project);
            this.modelManager = new ProjectModelManager(project, DefaultGradleModelLoader.createEmptyModel(projectDir));
            this.modelUpdater = new ProjectModelUpdater<>(createModelLoader(project), modelManager);
            this.settingsFileManager = new SettingsFileManager(projectDir, modelUpdater, modelManager.currentModel());
            this.projectDisplayInfo = new ProjectDisplayInfo(
                    modelManager.currentModel(),
                    commonProperties.displayNamePattern().getActiveSource());

            this.services = Lookups.fixed(serviceObjects.toArray());
            this.projectLookups = new NbGradleProjectLookups(project, services);
            this.extensions = new UpdatableProjectExtensions(projectLookups.getCombinedExtensionLookup());
        }

        public void updateExtensions(Collection<? extends NbGradleExtensionRef> newExtensions) {
            extensions.setExtensions(newExtensions);
            projectLookups.updateExtensions(newExtensions);
        }

        private static <T> T add(T obj, Collection<? super T> serviceContainer) {
            serviceContainer.add(obj);
            return obj;
        }

        private static DefaultGradleModelLoader createModelLoader(NbGradleProject project) {
            DefaultGradleModelLoader.Builder result = new DefaultGradleModelLoader.Builder(project);
            return result.create();
        }

        private static Path getSettingsDir(File projectDir) {
            Path settingsGradle = NbGradleModel.findSettingsGradle(projectDir);
            Path rootDir = settingsGradle != null ? settingsGradle.getParent() : null;
            return rootDir != null ? rootDir : projectDir.toPath();
        }

        private static LicenseManager<NbGradleModel> createLicenseManager() {
            NbFunction<NbGradleModel, PathResolver> pathResolverGetter = new NbFunction<NbGradleModel, PathResolver>() {
                @Override
                public PathResolver apply(NbGradleModel arg) {
                    return new LicensePathResolver(arg);
                }
            };

            NbFunction<NbGradleModel, String> modelNameGetter = new NbFunction<NbGradleModel, String>() {
                @Override
                public String apply(NbGradleModel arg) {
                    return arg.getSettingsDir().getFileName().toString();
                }
            };

            return new LicenseManager<>(pathResolverGetter, modelNameGetter);
        }
    }

    private static final class LicensePathResolver implements PathResolver {
        private final Path basePath;

        public LicensePathResolver(NbGradleModel model) {
            this.basePath = model.getSettingsDir();
        }

        @Override
        public Path resolvePath(Path relativePath) {
            return basePath.resolve(relativePath);
        }

        public Path getBasePath() {
            return basePath;
        }

        @Override
        public int hashCode() {
            return 485 + Objects.hashCode(basePath);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            final LicensePathResolver other = (LicensePathResolver)obj;
            return Objects.equals(this.basePath, other.basePath);
        }
    }
}
