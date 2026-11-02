FROM public.ecr.aws/lambda/python:3.13

ARG TORCH_VERSION=2.12.0
ARG EMBEDDING_MODEL=sdadas/mmlw-roberta-base
ARG EMBEDDING_MODEL_REVISION=bdeb29a43c3d58af1e6ed9d1836703a1ddfbe1d7

COPY src/requirements.txt ${LAMBDA_TASK_ROOT}/
RUN pip install --no-cache-dir torch==${TORCH_VERSION} --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/requirements.txt

RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${EMBEDDING_MODEL}', revision='${EMBEDDING_MODEL_REVISION}').save('/opt/model')" \
 && chmod -R a+rX /opt/model \
 && rm -rf /root/.cache

ENV EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    EMBEDDING_MODEL_DIR=/opt/model \
    HF_HUB_OFFLINE=1 \
    HF_HOME=/tmp/hf \
    TOKENIZERS_PARALLELISM=false

COPY src/app.py src/models.py src/settings.py ${LAMBDA_TASK_ROOT}/

CMD ["app.lambda_handler"]
