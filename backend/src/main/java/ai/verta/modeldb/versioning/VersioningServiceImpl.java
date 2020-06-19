package ai.verta.modeldb.versioning;

import ai.verta.common.ModelDBResourceEnum.ModelDBServiceResourceTypes;
import ai.verta.modeldb.ModelDBAuthInterceptor;
import ai.verta.modeldb.ModelDBException;
import ai.verta.modeldb.artifactStore.ArtifactStoreDAO;
import ai.verta.modeldb.authservice.AuthService;
import ai.verta.modeldb.authservice.RoleService;
import ai.verta.modeldb.experiment.ExperimentDAO;
import ai.verta.modeldb.experimentRun.ExperimentRunDAO;
import ai.verta.modeldb.monitoring.QPSCountResource;
import ai.verta.modeldb.monitoring.RequestLatencyResource;
import ai.verta.modeldb.project.ProjectDAO;
import ai.verta.modeldb.utils.ModelDBUtils;
import ai.verta.modeldb.versioning.ListRepositoriesRequest.Response;
import ai.verta.modeldb.versioning.VersioningServiceGrpc.VersioningServiceImplBase;
import ai.verta.modeldb.versioning.autogenerated._public.modeldb.versioning.model.AutogenBlob;
import ai.verta.modeldb.versioning.autogenerated._public.modeldb.versioning.model.AutogenBlobDiff;
import ai.verta.modeldb.versioning.blob.container.BlobContainer;
import ai.verta.modeldb.versioning.blob.visitors.Validator;
import ai.verta.uac.ModelDBActionEnum.ModelDBServiceActions;
import ai.verta.uac.UserInfo;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VersioningServiceImpl extends VersioningServiceImplBase {

  private static final Logger LOGGER = LogManager.getLogger(VersioningServiceImpl.class);
  private final AuthService authService;
  private final RoleService roleService;
  private final RepositoryDAO repositoryDAO;
  private final CommitDAO commitDAO;
  private final BlobDAO blobDAO;
  private final ProjectDAO projectDAO;
  private final ExperimentDAO experimentDAO;
  private final ExperimentRunDAO experimentRunDAO;
  private final ModelDBAuthInterceptor modelDBAuthInterceptor;
  private final FileHasher fileHasher;
  private final Validator validator = new Validator();
  private final ArtifactStoreDAO artifactStoreDAO;

  public VersioningServiceImpl(
      AuthService authService,
      RoleService roleService,
      RepositoryDAO repositoryDAO,
      CommitDAO commitDAO,
      BlobDAO blobDAO,
      ProjectDAO projectDAO,
      ExperimentDAO experimentDAO,
      ExperimentRunDAO experimentRunDAO,
      ModelDBAuthInterceptor modelDBAuthInterceptor,
      FileHasher fileHasher,
      ArtifactStoreDAO artifactStoreDAO) {
    this.authService = authService;
    this.roleService = roleService;
    this.repositoryDAO = repositoryDAO;
    this.commitDAO = commitDAO;
    this.blobDAO = blobDAO;
    this.projectDAO = projectDAO;
    this.experimentDAO = experimentDAO;
    this.experimentRunDAO = experimentRunDAO;
    this.modelDBAuthInterceptor = modelDBAuthInterceptor;
    this.fileHasher = fileHasher;
    this.artifactStoreDAO = artifactStoreDAO;
  }

  @Override
  public void listRepositories(
      ListRepositoriesRequest request, StreamObserver<Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        if (request.hasPagination()) {

          if (request.getPagination().getPageLimit() < 1
              || request.getPagination().getPageLimit() > 100) {
            throw new ModelDBException("Page limit is invalid", Code.INVALID_ARGUMENT);
          }
        }
        UserInfo userInfo = authService.getCurrentLoginUserInfo();
        Response response = repositoryDAO.listRepositories(request, userInfo);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListRepositoriesRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void getRepository(
      GetRepositoryRequest request,
      StreamObserver<GetRepositoryRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        GetRepositoryRequest.Response response = repositoryDAO.getRepository(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, GetRepositoryRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void createRepository(
      SetRepository request, StreamObserver<SetRepository.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        if (request.getRepository().getName().isEmpty()) {
          throw new ModelDBException("Repository name is empty", Code.INVALID_ARGUMENT);
        }

        roleService.validateEntityUserWithUserInfo(
            ModelDBServiceResourceTypes.REPOSITORY, null, ModelDBServiceActions.CREATE);
        UserInfo userInfo = authService.getCurrentLoginUserInfo();
        SetRepository.Builder requestBuilder = request.toBuilder();
        if (userInfo != null) {
          String vertaId = authService.getVertaIdFromUserInfo(userInfo);
          requestBuilder.setRepository(request.getRepository().toBuilder().setOwner(vertaId));
        }
        SetRepository.Response response =
            repositoryDAO.setRepository(commitDAO, requestBuilder.build(), userInfo, true);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(responseObserver, e, SetRepository.Response.getDefaultInstance());
    }
  }

  @Override
  public void updateRepository(
      SetRepository request, StreamObserver<SetRepository.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        if (request.getRepository().getName().isEmpty()
            && request.getRepository().getDescription().isEmpty()) {
          throw new ModelDBException(
              "Repository name and description is empty", Code.INVALID_ARGUMENT);
        } else if (request.getRepository().getName().isEmpty()) {
          throw new ModelDBException("Repository name should not be empty", Code.INVALID_ARGUMENT);
        }

        SetRepository.Response response =
            repositoryDAO.setRepository(commitDAO, request, null, false);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(responseObserver, e, SetRepository.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteRepository(
      DeleteRepositoryRequest request,
      StreamObserver<DeleteRepositoryRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        DeleteRepositoryRequest.Response response =
            repositoryDAO.deleteRepository(request, commitDAO, experimentRunDAO);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, DeleteRepositoryRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listCommits(
      ListCommitsRequest request, StreamObserver<ListCommitsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      ListCommitsRequest.Response response =
          commitDAO.listCommits(
              request,
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()));
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListCommitsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void getCommit(
      GetCommitRequest request, StreamObserver<GetCommitRequest.Response> responseObserver) {

    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        Commit commit =
            commitDAO.getCommit(
                request.getCommitSha(),
                (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()));
        responseObserver.onNext(GetCommitRequest.Response.newBuilder().setCommit(commit).build());
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, GetCommitRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void createCommit(
      CreateCommitRequest request, StreamObserver<CreateCommitRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      if (request.getBlobsCount() == 0) {
        if (request.getCommitBase().isEmpty() || request.getDiffsCount() == 0) {
          throw new ModelDBException(
              "Blob list should not be empty or commit base and diffs should be specified",
              Code.INVALID_ARGUMENT);
        }
      } else if (request.getCommit().getParentShasList().isEmpty()) {
        throw new ModelDBException(
            "Parent commits not found in the CreateCommitRequest", Code.INVALID_ARGUMENT);
      } else if (request.getBlobsCount() > 0
          && (!request.getCommitBase().isEmpty() || request.getDiffsCount() > 0)) {
        throw new ModelDBException(
            "Blob list and commit base with diffs should not be allowed together",
            Code.INVALID_ARGUMENT);
      }

      if (request.getCommit().getMessage().isEmpty()) {
        throw new ModelDBException("Commit message should not be empty", Code.INVALID_ARGUMENT);
      }

      List<BlobContainer> blobContainers;
      final RepositoryFunction repositoryFunction =
          (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId(), true);
      if (request.getBlobsCount() != 0) {
        blobContainers = validateBlobs(request);
      } else {
        List<AutogenBlobDiff> diffs = validateBlobDiffs(request);
        blobContainers =
            blobDAO.convertBlobDiffsToBlobs(
                diffs,
                repositoryFunction,
                (session, repository) ->
                    commitDAO.getCommitEntity(session, request.getCommitBase(), repository));
      }
      UserInfo currentLoginUserInfo = authService.getCurrentLoginUserInfo();

      CreateCommitRequest.Response response =
          commitDAO.setCommit(
              authService.getVertaIdFromUserInfo(currentLoginUserInfo),
              request.getCommit(),
              (session) -> blobDAO.setBlobs(session, blobContainers, fileHasher),
              (session, repoId, commitHash) ->
                  blobDAO.setBlobsAttributes(session, repoId, commitHash, blobContainers, true),
              repositoryFunction);

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, CreateCommitRequest.Response.getDefaultInstance());
    }
  }

  private List<AutogenBlobDiff> validateBlobDiffs(CreateCommitRequest request)
      throws ModelDBException {
    List<AutogenBlobDiff> diffs = new LinkedList<>();
    for (BlobDiff blobDiff : request.getDiffsList()) {
      AutogenBlobDiff autogenBlobDiff = AutogenBlobDiff.fromProto(blobDiff);
      validator.validate(autogenBlobDiff);
      diffs.add(autogenBlobDiff);
    }
    return diffs;
  }

  private List<BlobContainer> validateBlobs(CreateCommitRequest request) throws ModelDBException {
    List<BlobContainer> blobContainers = new LinkedList<>();
    for (BlobExpanded blobExpanded : request.getBlobsList()) {
      if (blobExpanded.getLocationList().isEmpty()) {
        throw new ModelDBException("Blob path should not be empty", Code.INVALID_ARGUMENT);
      }
      validator.validate(AutogenBlob.fromProto(blobExpanded.getBlob()));
      final BlobContainer blobContainer = BlobContainer.create(blobExpanded);
      blobContainers.add(blobContainer);
    }
    return blobContainers;
  }

  @Override
  public void deleteCommit(
      DeleteCommitRequest request, StreamObserver<DeleteCommitRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        DeleteCommitRequest.Response response = commitDAO.deleteCommit(request, repositoryDAO);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, DeleteCommitRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listCommitBlobs(
      ListCommitBlobsRequest request,
      StreamObserver<ListCommitBlobsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      if (request.getCommitSha().isEmpty()) {
        throw new ModelDBException("Commit SHA should not be empty", Code.INVALID_ARGUMENT);
      }

      ListCommitBlobsRequest.Response response =
          blobDAO.getCommitBlobsList(
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              request.getCommitSha(),
              request.getLocationPrefixList());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListCommitBlobsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listCommitExperimentRuns(
      ListCommitExperimentRunsRequest request,
      StreamObserver<ListCommitExperimentRunsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      if (request.getCommitSha().isEmpty()) {
        throw new ModelDBException("Commit SHA should not be empty", Code.INVALID_ARGUMENT);
      }

      ListCommitExperimentRunsRequest.Response response =
          experimentRunDAO.listCommitExperimentRuns(
              projectDAO,
              request,
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              (session, repository) ->
                  commitDAO.getCommitEntity(session, request.getCommitSha(), repository));
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListCommitExperimentRunsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listBlobExperimentRuns(
      ListBlobExperimentRunsRequest request,
      StreamObserver<ListBlobExperimentRunsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      if (request.getCommitSha().isEmpty()) {
        throw new ModelDBException("Commit SHA should not be empty", Code.INVALID_ARGUMENT);
      }

      ListBlobExperimentRunsRequest.Response response =
          experimentRunDAO.listBlobExperimentRuns(
              projectDAO,
              request,
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              (session, repository) ->
                  commitDAO.getCommitEntity(session, request.getCommitSha(), repository));
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListBlobExperimentRunsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void getCommitComponent(
      GetCommitComponentRequest request,
      StreamObserver<GetCommitComponentRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      if (request.getCommitSha().isEmpty()) {
        throw new ModelDBException("Commit SHA should not be empty", Code.INVALID_ARGUMENT);
      }

      GetCommitComponentRequest.Response response =
          blobDAO.getCommitComponent(
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              request.getCommitSha(),
              request.getLocationList());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, GetCommitComponentRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void computeRepositoryDiff(
      ComputeRepositoryDiffRequest request,
      StreamObserver<ComputeRepositoryDiffRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
      ComputeRepositoryDiffRequest.Response response =
          blobDAO.computeRepositoryDiff(repositoryDAO, request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ComputeRepositoryDiffRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void mergeRepositoryCommits(
      MergeRepositoryCommitsRequest request,
      StreamObserver<ai.verta.modeldb.versioning.MergeRepositoryCommitsRequest.Response>
          responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        MergeRepositoryCommitsRequest.Response mergeResponse =
            blobDAO.mergeCommit(repositoryDAO, request);
        responseObserver.onNext(mergeResponse);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, MergeRepositoryCommitsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void revertRepositoryCommits(
      RevertRepositoryCommitsRequest request,
      StreamObserver<RevertRepositoryCommitsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        RevertRepositoryCommitsRequest.Response mergeResponse =
            blobDAO.revertCommit(repositoryDAO, request);
        responseObserver.onNext(mergeResponse);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, RevertRepositoryCommitsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listBranches(
      ListBranchesRequest request, StreamObserver<ListBranchesRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        ListBranchesRequest.Response response = repositoryDAO.listBranches(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListBranchesRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void getBranch(
      GetBranchRequest request, StreamObserver<GetBranchRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        GetBranchRequest.Response response = repositoryDAO.getBranch(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, GetBranchRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void setBranch(
      SetBranchRequest request, StreamObserver<SetBranchRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        SetBranchRequest.Response response = repositoryDAO.setBranch(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, SetBranchRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteBranch(
      DeleteBranchRequest request, StreamObserver<DeleteBranchRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        if (request.getBranch().isEmpty()) {
          throw new ModelDBException(
              "Branch not found in the DeleteBranchRequest", Code.INVALID_ARGUMENT);
        }
        DeleteBranchRequest.Response response = repositoryDAO.deleteBranch(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, DeleteBranchRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listCommitsLog(
      ListCommitsLogRequest request,
      StreamObserver<ListCommitsLogRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        ListCommitsLogRequest.Response response = repositoryDAO.listCommitsLog(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, ListCommitsLogRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void listTags(
      ListTagsRequest request, StreamObserver<ListTagsRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        ListTagsRequest.Response response = repositoryDAO.listTags(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(responseObserver, e, ListTagsRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void getTag(
      GetTagRequest request, StreamObserver<GetTagRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        GetTagRequest.Response response = repositoryDAO.getTag(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(responseObserver, e, GetTagRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void setTag(
      SetTagRequest request, StreamObserver<SetTagRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        SetTagRequest.Response response = repositoryDAO.setTag(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(responseObserver, e, SetTagRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteTag(
      DeleteTagRequest request, StreamObserver<DeleteTagRequest.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        if (request.getTag().isEmpty()) {
          throw new ModelDBException(
              "Tag not found in the DeleteTagRequest", Code.INVALID_ARGUMENT);
        }
        DeleteTagRequest.Response response = repositoryDAO.deleteTag(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, DeleteTagRequest.Response.getDefaultInstance());
    }
  }

  @Override
  public void findRepositories(
      FindRepositories request, StreamObserver<FindRepositories.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {
        FindRepositories.Response response = repositoryDAO.findRepositories(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, FindRepositories.Response.getDefaultInstance());
    }
  }

  @Override
  public void findRepositoriesBlobs(
      FindRepositoriesBlobs request,
      StreamObserver<FindRepositoriesBlobs.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {

        FindRepositoriesBlobs.Response response = blobDAO.findRepositoriesBlobs(commitDAO, request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, FindRepositoriesBlobs.Response.getDefaultInstance());
    }
  }

  @Override
  public void getUrlForBlobVersioned(
      GetUrlForBlobVersioned request,
      StreamObserver<GetUrlForBlobVersioned.Response> responseObserver) {
    QPSCountResource.inc();
    try {
      try (RequestLatencyResource latencyResource =
          new RequestLatencyResource(modelDBAuthInterceptor.getMethodName())) {

        // Validate request parameters
        validateGetUrlForVersionedBlobRequest(request);

        GetUrlForBlobVersioned.Response response =
            blobDAO.getUrlForVersionedBlob(
                artifactStoreDAO,
                (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
                (session, repository) ->
                    commitDAO.getCommitEntity(session, request.getCommitSha(), repository),
                request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, GetUrlForBlobVersioned.Response.getDefaultInstance());
    }
  }

  private void validateGetUrlForVersionedBlobRequest(GetUrlForBlobVersioned request)
      throws ModelDBException {
    String errorMessage = null;
    if (request.getCommitSha().isEmpty()
        && request.getLocationList().isEmpty()
        && request.getMethod().isEmpty()
        && request.getPathDatasetComponentBlobPath().isEmpty()) {
      errorMessage =
          "Commit hash and Blob location and Method type AND Blob path not found in GetUrlForBlobVersioned request";
    } else if (request.getCommitSha().isEmpty()) {
      errorMessage = "Commit hash not found in GetUrlForBlobVersioned request";
    } else if (request.getLocationList().isEmpty()) {
      errorMessage = "Blob location not found in GetUrlForBlobVersioned request";
    } else if (request.getPathDatasetComponentBlobPath().isEmpty()) {
      errorMessage = "Blob path not found in GetUrlForBlobVersioned request";
    } else if (request.getMethod().isEmpty()) {
      errorMessage = "Method is not found in GetUrlForBlobVersioned request";
    }
    if (errorMessage != null) {
      LOGGER.warn(errorMessage);
      throw new ModelDBException(errorMessage, Code.INVALID_ARGUMENT);
    }
  }

  @Override
  public void commitVersionedBlobArtifactPart(
      CommitVersionedBlobArtifactPart request,
      StreamObserver<CommitVersionedBlobArtifactPart.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(ModelDBAuthInterceptor.METHOD_NAME.get())) {
      String errorMessage = null;
      if (request.getCommitSha().isEmpty()
          && request.getLocationList().isEmpty()
          && !request.hasArtifactPart()) {
        errorMessage =
            "Commit hash and Location and Artifact Part not found in CommitVersionedBlobArtifactPart request";
      } else if (request.getCommitSha().isEmpty()) {
        errorMessage = "Commit hash not found in CommitVersionedBlobArtifactPart request";
      } else if (request.getLocationList().isEmpty()) {
        errorMessage = "Location not found in CommitVersionedBlobArtifactPart request";
      } else if (!request.hasArtifactPart()) {
        errorMessage = "Artifact Part not found in CommitVersionedBlobArtifactPart request";
      }

      if (errorMessage != null) {
        LOGGER.warn(errorMessage);
        throw new ModelDBException(errorMessage, Code.INVALID_ARGUMENT);
      }

      CommitVersionedBlobArtifactPart.Response response =
          blobDAO.commitVersionedBlobArtifactPart(
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              (session, repository) ->
                  commitDAO.getCommitEntity(session, request.getCommitSha(), repository),
              request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, CommitVersionedBlobArtifactPart.Response.getDefaultInstance());
    }
  }

  @Override
  public void getCommittedVersionedBlobArtifactParts(
      GetCommittedVersionedBlobArtifactParts request,
      StreamObserver<GetCommittedVersionedBlobArtifactParts.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(ModelDBAuthInterceptor.METHOD_NAME.get())) {
      String errorMessage = null;
      if (request.getCommitSha().isEmpty() && request.getLocationList().isEmpty()) {
        errorMessage =
            "Commit hash and Location not found in GetCommittedVersionedBlobArtifactParts request";
      } else if (request.getCommitSha().isEmpty()) {
        errorMessage = "Commit hash not found in GetCommittedVersionedBlobArtifactParts request";
      } else if (request.getLocationList().isEmpty()) {
        errorMessage = "Location not found in GetCommittedVersionedBlobArtifactParts request";
      }

      if (errorMessage != null) {
        LOGGER.warn(errorMessage);
        throw new ModelDBException(errorMessage, Code.INVALID_ARGUMENT);
      }

      GetCommittedVersionedBlobArtifactParts.Response response =
          blobDAO.getCommittedVersionedBlobArtifactParts(
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              (session, repository) ->
                  commitDAO.getCommitEntity(session, request.getCommitSha(), repository),
              request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver,
          e,
          GetCommittedVersionedBlobArtifactParts.Response.getDefaultInstance());
    }
  }

  @Override
  public void commitMultipartVersionedBlobArtifact(
      CommitMultipartVersionedBlobArtifact request,
      StreamObserver<CommitMultipartVersionedBlobArtifact.Response> responseObserver) {
    QPSCountResource.inc();
    try (RequestLatencyResource latencyResource =
        new RequestLatencyResource(ModelDBAuthInterceptor.METHOD_NAME.get())) {
      String errorMessage = null;
      if (request.getCommitSha().isEmpty()
          && request.getLocationList().isEmpty()
          && request.getPathDatasetComponentBlobPath().isEmpty()) {
        errorMessage =
            "Commit hash and Location and path not found in CommitMultipartVersionedBlobArtifact request";
      } else if (request.getCommitSha().isEmpty()) {
        errorMessage = "Commit hash not found in CommitMultipartVersionedBlobArtifact request";
      } else if (request.getLocationList().isEmpty()) {
        errorMessage = "Location not found in CommitMultipartVersionedBlobArtifact request";
      } else if (request.getPathDatasetComponentBlobPath().isEmpty()) {
        errorMessage = "Path not found in CommitMultipartVersionedBlobArtifact request";
      }

      if (errorMessage != null) {
        LOGGER.warn(errorMessage);
        throw new ModelDBException(errorMessage, Code.INVALID_ARGUMENT);
      }

      CommitMultipartVersionedBlobArtifact.Response response =
          blobDAO.commitMultipartVersionedBlobArtifact(
              (session) -> repositoryDAO.getRepositoryById(session, request.getRepositoryId()),
              (session, repository) ->
                  commitDAO.getCommitEntity(session, request.getCommitSha(), repository),
              request,
              artifactStoreDAO::commitMultipart);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      ModelDBUtils.observeError(
          responseObserver, e, CommitMultipartVersionedBlobArtifact.Response.getDefaultInstance());
    }
  }
}
