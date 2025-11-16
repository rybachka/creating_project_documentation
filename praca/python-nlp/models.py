from typing import List, Optional, Literal, Dict
from pydantic import BaseModel, Field

class ParamIn(BaseModel):
    name: str
    in_: Optional[str] = Field(default=None, alias="in")
    type: Optional[str] = None
    required: Optional[bool] = None
    description: Optional[str] = None

    model_config = {
        "populate_by_name": True,
        "extra": "ignore",
    }

class ReturnDoc(BaseModel):
    type: Optional[str] = None
    description: Optional[str] = None

class DescribeIn(BaseModel):
    symbol: str
    kind: Literal["endpoint","function","method","dto"] = "endpoint"

    operationId: Optional[str] = None
    method: Optional[str] = None
    path: Optional[str] = None
    rawComment: Optional[str] = None
    requestBody: Optional[dict] = None
    implNotes: Optional[List[str]] = None
    
    signature: Optional[str] = None
    comment: Optional[str] = None
    http: Optional[str] = None
    pathTemplate: Optional[str] = None
    javadoc: Optional[str] = None
    notes: Optional[List[str]] = None
    todos: Optional[List[str]] = None
    language: Optional[str] = "pl"
    params: Optional[List[ParamIn]] = None
    returns: Optional[ReturnDoc] = None

class ParamDoc(BaseModel):
    name: str
    doc: str

class ExampleReq(BaseModel):
    curl: str

class ExampleResp(BaseModel):
    status: int = Field(200, ge=100, le=599)
    body: Dict[str, object] = Field(default_factory=dict)

class Examples(BaseModel):
    requests: List[ExampleReq] = Field(default_factory=list, max_items=2)
    response: ExampleResp

class DescribeOut(BaseModel):
    summary: Optional[str] = ""
    shortDescription: Optional[str] = ""
    mediumDescription: Optional[str] = ""
    longDescription: Optional[str] = ""

    paramDocs: List[ParamDoc] = []
    returnDoc: Optional[str] = ""
    notes: Optional[List[str]] = None
    examples: Optional[Dict] = None

